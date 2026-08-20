package com.anonchat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.anonchat.app.model.ChatMessage
import com.anonchat.app.model.SavedChat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Foreground service that listens to all user chat threads in Firebase
 * and shows notifications when new messages arrive from other users.
 * Automatically restores deleted chats if a new message arrives from a partner.
 */
class MessageNotificationService : Service() {

    companion object {
        const val CHANNEL_ID = "anonchat_messages"
        const val FOREGROUND_CHANNEL_ID = "anonchat_service"
        const val FOREGROUND_NOTIFICATION_ID = 1
        private var isRunning = false

        /** Set by SavedChatActivity when it's open, cleared when closed */
        var activeChatId: String? = null

        /** Set by MainActivity when connected to a live chat thread */
        var activeThreadId: String? = null

        /** Set true while user is active on the live chat screen */
        var isLiveChatActive: Boolean = false

        fun start(context: Context) {
            if (isRunning) return
            val intent = Intent(context, MessageNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MessageNotificationService::class.java))
        }
    }

    private val listeners = mutableMapOf<String, ChildEventListener>()
    private var userThreadsListener: ChildEventListener? = null
    private var notificationId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannels()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        startListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopAllListeners()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // Channel for the persistent foreground notification
            val serviceChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID, "AnonChat Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps AnonChat connected for messages" }
            nm.createNotificationChannel(serviceChannel)

            // Channel for message notifications
            val msgChannel = NotificationChannel(
                CHANNEL_ID, "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New chat messages"
                enableVibration(true)
            }
            nm.createNotificationChannel(msgChannel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, ChatListActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("AnonChat")
            .setContentText("Connected — listening for messages")
            .setSmallIcon(R.drawable.ic_chat_room)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startListening() {
        val currentUserId = TestSession.currentUserId(this) ?: return

        // 1. Listen to /user_threads/$currentUserId so deleted chats still receive new messages
        listenToUserThreads(currentUserId)

        // 2. Listen to all local saved chats
        val chats = ChatStorage.getSavedChats(this)
        chats.forEach { chat ->
            val threadId = chat.threadId ?: deriveThreadId(chat, currentUserId) ?: return@forEach
            UserDatabase.registerUserThread(currentUserId, chat.partnerAccountId, threadId)
            attachThreadListener(threadId, currentUserId)
        }
    }

    private fun listenToUserThreads(currentUserId: String) {
        if (userThreadsListener != null) return
        val db = FirebaseDatabase.getInstance().reference.child("user_threads").child(currentUserId)
        userThreadsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val threadId = snapshot.key ?: return
                attachThreadListener(threadId, currentUserId)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }
        db.addChildEventListener(userThreadsListener!!)
    }

    private fun attachThreadListener(threadId: String, currentUserId: String) {
        if (listeners.containsKey(threadId)) return

        val ref = FirebaseDatabase.getInstance().reference
            .child("threads").child(threadId).child("messages")

        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val senderId = snapshot.child("senderId").getValue(String::class.java) ?: return
                if (senderId == currentUserId) return // own message

                val msgId = snapshot.child("id").getValue(String::class.java) ?: return
                val senderName = snapshot.child("senderName").getValue(String::class.java) ?: "Someone"
                val text = snapshot.child("message").getValue(String::class.java) ?: ""
                val ts = snapshot.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()

                // Keep thread registered under /user_threads/$currentUserId
                UserDatabase.registerUserThread(currentUserId, senderId, threadId)

                var existingChat = ChatStorage.getSavedChats(this@MessageNotificationService)
                    .find { it.threadId == threadId || (it.partnerAccountId == senderId && !it.partnerAccountId.isNullOrBlank()) }

                val targetChatId: String
                val msg = ChatMessage(msgId, senderId, senderName, text, ts, "delivered")

                if (existingChat != null) {
                    targetChatId = existingChat.id
                    if (existingChat.messages.none { it.id == msgId }) {
                        ChatStorage.appendMessageToChat(this@MessageNotificationService, targetChatId, msg)
                    }
                } else {
                    // Chat was DELETED or missing locally — AUTO-RESTORE IT!
                    targetChatId = java.util.UUID.randomUUID().toString()
                    val myName = TestSession.cachedDisplayName(this@MessageNotificationService, currentUserId) ?: "AnnoUser"
                    val restoredChat = SavedChat(
                        id = targetChatId,
                        savedAt = ts,
                        userName = myName,
                        partnerName = senderName,
                        partnerAccountId = senderId,
                        threadId = threadId,
                        partnerGender = null,
                        partnerAge = null,
                        partnerCity = null,
                        partnerAvatar = null,
                        messages = listOf(msg)
                    )
                    ChatStorage.saveChat(this@MessageNotificationService, restoredChat)

                    // Asynchronously hydrate partner profile details from /users/$senderId
                    FirebaseDatabase.getInstance().reference.child("users").child(senderId)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(userSnap: DataSnapshot) {
                                val profileSnap = userSnap.child("profile")
                                val dbName = profileSnap.child("displayName").getValue(String::class.java) ?: senderName
                                val gender = profileSnap.child("gender").getValue(String::class.java)
                                val age = profileSnap.child("age").getValue(Long::class.java)?.toInt()
                                val city = profileSnap.child("city").getValue(String::class.java)
                                val avatar = userSnap.child("avatar").getValue(String::class.java)
                                val updated = restoredChat.copy(
                                    partnerName = dbName,
                                    partnerGender = gender,
                                    partnerAge = age,
                                    partnerCity = city,
                                    partnerAvatar = avatar
                                )
                                ChatStorage.updateSavedChat(this@MessageNotificationService, updated)
                            }
                            override fun onCancelled(error: DatabaseError) {}
                        })
                }

                // Show notification ONLY if user is NOT currently in this chat / thread or active live chat screen
                val isUserInThisChat = (activeChatId != null && activeChatId == targetChatId)
                        || (activeThreadId != null && activeThreadId == threadId)
                        || isLiveChatActive

                if (!isUserInThisChat) {
                    showMessageNotification(targetChatId, senderName, text)
                }
            }

            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }

        ref.orderByChild("timestamp").addChildEventListener(listener)
        listeners[threadId] = listener
    }

    private fun stopAllListeners() {
        val currentUserId = TestSession.currentUserId(this)
        if (currentUserId != null && userThreadsListener != null) {
            FirebaseDatabase.getInstance().reference
                .child("user_threads").child(currentUserId)
                .removeEventListener(userThreadsListener!!)
            userThreadsListener = null
        }
        listeners.forEach { (threadId, listener) ->
            FirebaseDatabase.getInstance().reference
                .child("threads").child(threadId).child("messages")
                .removeEventListener(listener)
        }
        listeners.clear()
    }

    private fun showMessageNotification(chatId: String, senderName: String, text: String) {
        val intent = Intent(this, SavedChatActivity::class.java).apply {
            putExtra("CHAT_ID", chatId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(this, chatId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(senderName)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_chat_room)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_VIBRATE or Notification.DEFAULT_SOUND)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(notificationId++, notification)
    }

    private fun deriveThreadId(chat: SavedChat, currentUserId: String): String? {
        val partnerId = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != currentUserId }?.senderId
            ?: return null
        return listOf(currentUserId, partnerId).sorted().joinToString("_")
    }
}
