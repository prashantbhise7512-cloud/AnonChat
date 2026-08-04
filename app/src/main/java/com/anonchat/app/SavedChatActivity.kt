package com.anonchat.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.adapter.MessageAdapter
import com.anonchat.app.model.ChatMessage
import com.anonchat.app.model.SavedChat
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import de.hdodenhof.circleimageview.CircleImageView
import java.util.UUID

class SavedChatActivity : AppCompatActivity() {

    private var chatId: String = ""
    private lateinit var adapter: MessageAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var threadRef: com.google.firebase.database.DatabaseReference? = null
    private var threadListener: ChildEventListener? = null
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var myId: String
    private lateinit var chat: SavedChat
    // Custom header views
    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvToolbarSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_chat)

        chatId = intent.getStringExtra("CHAT_ID") ?: run { finish(); return }

        // Mark this chat as read
        val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
        prefs.edit().putLong("read_time_$chatId", System.currentTimeMillis()).apply()

        val btnOverflowMenu = findViewById<ImageView>(R.id.btnOverflowMenu)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        val editMessage = findViewById<EditText>(R.id.editMessage)
        val btnSend = findViewById<FrameLayout>(R.id.btnSend)
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)
        tvToolbarSubtitle = findViewById(R.id.tvToolbarSubtitle)
        val btnBack = findViewById<ImageView>(R.id.btnBack)

        val chats = ChatStorage.getSavedChats(this)
        chat = chats.find { it.id == chatId } ?: run { finish(); return }
        backfillChatData()

        val partnerName = chat.messages.firstOrNull { it.senderName != chat.userName }?.senderName
            ?: chat.partnerName.ifEmpty { "AnonUser" }
        tvToolbarTitle.text = partnerName
        tvToolbarSubtitle.text = "last seen..."
        btnBack.setOnClickListener { finish() }

        loadLastActive(chat)
        loadToolbarAvatar(chat)

        // Tapping title/avatar shows partner profile
        val toolbarTitleBlock = findViewById<LinearLayout>(R.id.toolbarTitleBlock)
        toolbarTitleBlock.setOnClickListener { showPartnerProfileDialog(chat) }
        findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.ivToolbarAvatar)
            .setOnClickListener { showPartnerProfileDialog(chat) }

        myId = chat.messages.firstOrNull { it.senderName == chat.userName }?.senderId
            ?: TestSession.currentUserId(this)
            ?: TestSession.uid(this)
            ?: ""

        messages.addAll(chat.messages)
        adapter = MessageAdapter(myId)
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerMessages.adapter = adapter
        adapter.submitList(messages.toList())

        // Listen for new Firebase messages if a thread exists (or can be derived from the saved partner id)
        val resolvedThreadId = resolveThreadId()
        if (resolvedThreadId != null) {
            listenForNewMessages(resolvedThreadId)
        }

        // Send button
        btnSend.alpha = 0.5f
        editMessage.addTextChangedListener { text ->
            btnSend.alpha = if (text.isNullOrBlank()) 0.5f else 1.0f
        }

        btnSend.setOnClickListener {
            val text = editMessage.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isEmpty()) return@setOnClickListener

            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                senderId = myId,
                senderName = chat.userName,
                message = text,
                timestamp = System.currentTimeMillis(),
                status = "sent"
            )

            messages.add(msg)
            adapter.submitList(messages.toList())
            recyclerMessages.scrollToPosition(messages.size - 1)
            persistMessages()
            editMessage.text?.clear()

            // Write to Firebase thread so partner receives in real time
            val resolvedThreadId = resolveThreadId()
            if (resolvedThreadId != null) {
                val messagePayload = mapOf(
                    "id" to msg.id,
                    "senderId" to msg.senderId,
                    "senderName" to msg.senderName,
                    "message" to msg.message,
                    "timestamp" to msg.timestamp,
                    "status" to "sent"
                )

                FirebaseDatabase.getInstance().reference
                    .child("chatThreads").child(resolvedThreadId).child("messages")
                    .push().setValue(messagePayload)

                val threadRef = FirebaseDatabase.getInstance().reference
                    .child("threads").child(resolvedThreadId)
                threadRef.child("participants").setValue(
                    listOfNotNull(myId, chat.partnerAccountId).distinct()
                )
                threadRef.child("createdAt").setValue(com.google.firebase.database.ServerValue.TIMESTAMP)
                threadRef.child("messages").push().setValue(messagePayload)
            }
        }

        btnOverflowMenu.setOnClickListener { view -> showOverflowMenu(view) }
    }

    // Listen to /threads/{threadId}/messages starting from last saved message
    private fun listenForNewMessages(threadId: String) {
        val lastTs = messages.lastOrNull()?.timestamp ?: 0L

        threadRef = FirebaseDatabase.getInstance().reference
            .child("chatThreads").child(threadId).child("messages")

        threadListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val msgId = snapshot.child("id").getValue(String::class.java) ?: return
                val senderId = snapshot.child("senderId").getValue(String::class.java) ?: return
                val senderName = snapshot.child("senderName").getValue(String::class.java) ?: return
                val text = snapshot.child("message").getValue(String::class.java) ?: return
                val ts = snapshot.child("timestamp").getValue(Long::class.java) ?: return

                // Skip already-loaded messages
                if (messages.any { it.id == msgId }) return

                val msg = ChatMessage(
                    id = msgId,
                    senderId = senderId,
                    senderName = senderName,
                    message = text,
                    timestamp = ts,
                    status = if (senderId == myId) "sent" else "read"
                )
                messages.add(msg)
                messages.sortBy { it.timestamp }
                adapter.submitList(messages.toList())
                recyclerMessages.scrollToPosition(messages.size - 1)
                persistMessages()

                // Update unread timestamp
                val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                prefs.edit().putLong("read_time_$chatId", System.currentTimeMillis()).apply()

                // Mark message as read in Firebase
                if (senderId != myId) {
                    snapshot.ref.child("status").setValue("read")
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {
                // Update tick status when partner reads our messages
                val msgId = s.child("id").getValue(String::class.java) ?: return
                val status = s.child("status").getValue(String::class.java) ?: return
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx >= 0) {
                    messages[idx] = messages[idx].copy(status = status)
                    adapter.submitList(messages.toList())
                }
            }
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }

        threadRef!!.orderByChild("timestamp").startAt(lastTs.toDouble())
            .addChildEventListener(threadListener!!)
    }

    private fun resolveThreadId(): String? {
        chat.threadId?.takeIf { it.isNotBlank() }?.let { return it }
        val currentUid = TestSession.currentUserId(this) ?: TestSession.uid(this)
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != currentUid }?.senderId
        if (!currentUid.isNullOrBlank() && !partnerUid.isNullOrBlank()) {
            return listOf(currentUid, partnerUid).sorted().joinToString("_")
        }

        val distinctSenderIds = chat.messages.map { it.senderId }.distinct()
        return if (distinctSenderIds.size == 2) distinctSenderIds.sorted().joinToString("_") else null
    }

    private fun backfillChatData() {
        val currentUid = TestSession.currentUserId(this) ?: TestSession.uid(this)
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != currentUid }?.senderId
        val threadId = resolveThreadId()

        if (partnerUid != chat.partnerAccountId || threadId != chat.threadId) {
            chat = chat.copy(
                partnerAccountId = partnerUid ?: chat.partnerAccountId,
                threadId = threadId ?: chat.threadId
            )
            ChatStorage.updateSavedChat(this, chat)
        }
    }

    private fun persistMessages() {
        val allChats = ChatStorage.getSavedChats(this).toMutableList()
        val idx = allChats.indexOfFirst { it.id == chatId }
        if (idx >= 0) {
            allChats[idx] = allChats[idx].copy(messages = messages.toList())
            ChatStorage.persistChats(this, allChats)
        }
    }

    private fun loadLastActive(chat: SavedChat) {
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId }?.senderId
            ?: return
        if (!AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("lastActive")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        tvToolbarSubtitle.text = formatLastActive(snapshot.getValue(Long::class.java))
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        } else {
            val ts = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                .getLong("last_active_$partnerUid", 0L)
            tvToolbarSubtitle.text = formatLastActive(ts.takeIf { it > 0 })
        }
    }

    private fun formatLastActive(timestamp: Long?): String {
        if (timestamp == null || timestamp == 0L) return "last active recently"

        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val now = java.util.Calendar.getInstance()
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())

        val timeString = timeFormat.format(java.util.Date(timestamp))
        val dateString = dateFormat.format(java.util.Date(timestamp))

        return when {
            calendar.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                calendar.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) ->
                "last active today at $timeString"
            calendar.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
                calendar.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) - 1 ->
                "last active yesterday at $timeString"
            else -> "last active $dateString at $timeString"
        }
    }

    private fun loadToolbarAvatar(chat: SavedChat) {
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId }?.senderId
            ?: return
        if (!AuthActivity.TEST_MODE) {
            val iv = findViewById<CircleImageView>(R.id.ivToolbarAvatar)
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("avatar")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val data = snapshot.getValue(String::class.java) ?: return
                        try {
                            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                            iv.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        } catch (_: Exception) {}
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }
    }

    private fun showOverflowMenu(view: android.view.View) {
        val popup = PopupMenu(this, view)
        popup.menuInflater.inflate(R.menu.menu_saved_chat, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete -> {
                    AlertDialog.Builder(this)
                        .setTitle("Delete saved chat?")
                        .setMessage("This cannot be undone.")
                        .setPositiveButton("Delete") { _, _ ->
                            ChatStorage.deleteChat(this, chatId)
                            finish()
                        }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
                R.id.action_block -> {
                    AlertDialog.Builder(this)
                        .setTitle("Block this user?")
                        .setMessage("You won't be matched with them again.")
                        .setPositiveButton("Block") { _, _ ->
                            val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                            val blocked = prefs.getStringSet("blocked_users", mutableSetOf())?.toMutableSet()
                                ?: mutableSetOf()
                            chat.partnerAccountId?.let { blocked.add(it) }
                            prefs.edit().putStringSet("blocked_users", blocked).apply()
                            ChatStorage.deleteChat(this, chatId)
                            Toast.makeText(this, "User blocked", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        .setNegativeButton("Cancel", null).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onDestroy() {
        super.onDestroy()
        threadListener?.let { threadRef?.removeEventListener(it) }
    }

    private fun showPartnerProfileDialog(chat: SavedChat) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_partner_profile, null)
        val ivAvatar = dialogView.findViewById<CircleImageView>(R.id.ivPartnerAvatar)
        val tvName = dialogView.findViewById<TextView>(R.id.tvPartnerName)
        val tvGender = dialogView.findViewById<TextView>(R.id.tvPartnerGender)
        val tvAge = dialogView.findViewById<TextView>(R.id.tvPartnerAge)
        val tvCity = dialogView.findViewById<TextView>(R.id.tvPartnerCity)

        tvName.text = chat.partnerName.ifEmpty { "AnonUser" }
        tvGender.text = chat.partnerGender ?: "Not specified"
        tvAge.text = if (chat.partnerAge != null) chat.partnerAge.toString() else "Not specified"
        tvCity.text = chat.partnerCity ?: "Not specified"

        ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        when (chat.partnerGender) {
            "Female" -> ivAvatar.borderColor = android.graphics.Color.parseColor("#E91E63")
            else -> ivAvatar.borderColor = resources.getColor(R.color.primary, theme)
        }

        var fetchedAvatarBase64: String? = null
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId }?.senderId
        val cachedName = partnerUid?.let { TestSession.cachedProfileDisplayName(this, it) }
        val cachedGender = partnerUid?.let { TestSession.cachedProfileGender(this, it) }
        val cachedAge = partnerUid?.let { TestSession.cachedProfileAge(this, it) }
        val cachedCity = partnerUid?.let { TestSession.cachedProfileCity(this, it) }
        val cachedAvatar = partnerUid?.let { TestSession.cachedProfileAvatar(this, it) }

        if (!cachedName.isNullOrBlank() || !cachedGender.isNullOrBlank() || cachedAge != null || !cachedCity.isNullOrBlank() || !cachedAvatar.isNullOrBlank()) {
            tvName.text = cachedName ?: chat.partnerName.ifEmpty { "AnonUser" }
            tvGender.text = cachedGender ?: chat.partnerGender ?: "Not specified"
            tvAge.text = if (cachedAge != null && cachedAge >= 0) cachedAge.toString() else if (chat.partnerAge != null) chat.partnerAge.toString() else "Not specified"
            tvCity.text = cachedCity ?: chat.partnerCity ?: "Not specified"
            if (!cachedAvatar.isNullOrEmpty()) {
                fetchedAvatarBase64 = cachedAvatar
                try {
                    val bytes = android.util.Base64.decode(cachedAvatar, android.util.Base64.DEFAULT)
                    ivAvatar.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                } catch (_: Exception) {}
            }
        }

        if (partnerUid != null && !AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val profile = snapshot.child("profile")
                        val fetchedName = profile.child("displayName").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: cachedName ?: chat.partnerName.ifEmpty { "AnonUser" }
                        val fetchedGender = profile.child("gender").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: cachedGender ?: chat.partnerGender ?: "Not specified"
                        val fetchedAge = profile.child("age").getValue(Long::class.java)?.toInt() ?: cachedAge
                        val fetchedCity = profile.child("city").getValue(String::class.java)
                            ?.takeIf { it.isNotBlank() }
                            ?: cachedCity ?: chat.partnerCity ?: "Not specified"

                        tvName.text = fetchedName
                        tvGender.text = fetchedGender
                        tvAge.text = if (fetchedAge != null && fetchedAge >= 0) fetchedAge.toString() else "Not specified"
                        tvCity.text = fetchedCity

                        val data = snapshot.child("avatar").getValue(String::class.java)
                        if (!data.isNullOrEmpty()) {
                            fetchedAvatarBase64 = data
                            try {
                                val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                                ivAvatar.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                            } catch (_: Exception) {}
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }

        ivAvatar.setOnClickListener {
            val base64 = fetchedAvatarBase64
            if (base64 != null) {
                val intent = Intent(this, PhotoViewActivity::class.java)
                intent.putExtra(PhotoViewActivity.EXTRA_IMAGE_BASE64, base64)
                startActivity(intent)
            } else {
                Toast.makeText(this, "No profile picture available", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Close", null)
            .show()
    }
}
