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
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase

/**
 * Foreground service that listens to all saved chat threads in Firebase
 * and shows notifications when new messages arrive from other users.
 * No Cloud Functions or Blaze plan needed.
 */
class MessageNotificationService : Service() {

    companion object {
        const val CHANNEL_ID = "anonchat_messages"
        const val FOREGROUND_CHANNEL_ID = "anonchat_service"
        const val FOREGROUND_NOTIFICATION_ID = 1
        private var isRunning = false

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
        val chats = ChatStorage.getSavedChats(this)

        chats.forEach { chat ->
            val threadId = chat.threadId ?: deriveThreadId(chat, currentUserId) ?: return@forEach
            if (listeners.containsKey(threadId)) return@forEach

            val ref = FirebaseDatabase.getInstance().reference
                .child("threads").child(threadId).child("messages")

            val listener = object : ChildEventListener {
                override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                    val senderId = snapshot.child("senderId").getValue(String::class.java) ?: return
                    if (senderId == currentUserId) return // own message

                    val msgId = snapshot.child("id").getValue(String::class.java) ?: return
                    val senderName = snapshot.child("senderName").getValue(String::class.java) ?: "Someone"
                    val text = snapshot.child("message").getValue(String::class.java) ?: ""
                    val ts = snapshot.child("timestamp").getValue(Long::class.java) ?: return

                    // Check if already seen
                    val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                    val lastRead = prefs.getLong("read_time_${chat.id}", 0L)
                    if (ts <= lastRead) return

                    // Check if already in local messages
                    val existingChat = ChatStorage.getSavedChats(this@MessageNotificationService)
                        .find { it.id == chat.id }
                    if (existingChat?.messages?.any { it.id == msgId } == true) return

                    // Save message locally
                    val msg = ChatMessage(msgId, senderId, senderName, text, ts, "delivered")
                    ChatStorage.appendMessageToChat(this@MessageNotificationService, chat.id, msg)

                    // Show notification
                    showMessageNotification(chat.id, senderName, text)
                }
                override fun onChildChanged(s: DataSnapshot, p: String?) {}
                override fun onChildRemoved(s: DataSnapshot) {}
                override fun onChildMoved(s: DataSnapshot, p: String?) {}
                override fun onCancelled(e: DatabaseError) {}
            }

            ref.orderByChild("timestamp").addChildEventListener(listener)
            listeners[threadId] = listener
        }
    }

    private fun stopAllListeners() {
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

    private fun deriveThreadId(chat: com.anonchat.app.model.SavedChat, currentUserId: String): String? {
        val partnerId = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != currentUserId }?.senderId
            ?: return null
        return listOf(currentUserId, partnerId).sorted().joinToString("_")
    }
}
