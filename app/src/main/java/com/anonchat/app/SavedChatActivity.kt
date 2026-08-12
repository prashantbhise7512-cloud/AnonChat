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

    private lateinit var tvToolbarTitle: TextView
    private lateinit var tvToolbarSubtitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_chat)

        chatId = intent.getStringExtra("CHAT_ID") ?: run { finish(); return }

        // Mark as read
        getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
            .edit().putLong("read_time_$chatId", System.currentTimeMillis()).apply()

        val btnOverflowMenu = findViewById<ImageView>(R.id.btnOverflowMenu)
        val btnBack = findViewById<ImageView>(R.id.btnBack)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        val editMessage = findViewById<EditText>(R.id.editMessage)
        val btnSend = findViewById<FrameLayout>(R.id.btnSend)
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle)
        tvToolbarSubtitle = findViewById(R.id.tvToolbarSubtitle)

        val chats = ChatStorage.getSavedChats(this)
        chat = chats.find { it.id == chatId } ?: run { finish(); return }

        // Resolve my user id
        myId = TestSession.currentUserId(this) ?: ""

        val partnerName = chat.partnerName.ifEmpty {
            chat.messages.firstOrNull { it.senderId != myId }?.senderName ?: "AnnoUser"
        }
        tvToolbarTitle.text = partnerName
        tvToolbarSubtitle.text = "..."
        btnBack.setOnClickListener { finish() }

        loadLastActive()
        loadToolbarAvatar()

        val toolbarTitleBlock = findViewById<LinearLayout>(R.id.toolbarTitleBlock)
        toolbarTitleBlock.setOnClickListener { showPartnerProfileDialog() }
        findViewById<CircleImageView>(R.id.ivToolbarAvatar).setOnClickListener { showPartnerProfileDialog() }

        // Load messages
        messages.addAll(chat.messages)
        adapter = MessageAdapter(myId)
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerMessages.adapter = adapter
        adapter.submitList(messages.toList())

        // Resolve thread id and listen for new messages
        val threadId = getThreadId()
        if (threadId != null && !AuthActivity.TEST_MODE) {
            listenForNewMessages(threadId)
        }

        // Send
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

            // Write to Firebase /threads/{threadId}/messages
            val tid = getThreadId()
            if (tid != null && !AuthActivity.TEST_MODE) {
                FirebaseDatabase.getInstance().reference
                    .child("threads").child(tid).child("messages")
                    .push().setValue(mapOf(
                        "id" to msg.id,
                        "senderId" to msg.senderId,
                        "senderName" to msg.senderName,
                        "message" to msg.message,
                        "timestamp" to msg.timestamp,
                        "status" to "sent"
                    ))
            }
        }

        btnOverflowMenu.setOnClickListener { view -> showOverflowMenu(view) }
    }

    /** Derive a stable thread id from both user ids (sorted). Same on both devices. */
    private fun getThreadId(): String? {
        if (chat.threadId != null && chat.threadId!!.isNotBlank()) return chat.threadId

        val partnerId = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId }?.senderId
            ?: return null

        val threadId = listOf(myId, partnerId).sorted().joinToString("_")

        // Backfill threadId into local storage if missing
        if (chat.threadId == null) {
            chat = chat.copy(threadId = threadId)
            val allChats = ChatStorage.getSavedChats(this).toMutableList()
            val idx = allChats.indexOfFirst { it.id == chatId }
            if (idx >= 0) {
                allChats[idx] = chat
                ChatStorage.persistChats(this, allChats)
            }
        }
        return threadId
    }

    private fun listenForNewMessages(threadId: String) {
        threadRef = FirebaseDatabase.getInstance().reference
            .child("threads").child(threadId).child("messages")

        threadListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val msgId = snapshot.child("id").getValue(String::class.java) ?: return
                if (messages.any { it.id == msgId }) return // already loaded

                val senderId = snapshot.child("senderId").getValue(String::class.java) ?: return
                val senderName = snapshot.child("senderName").getValue(String::class.java) ?: return
                val text = snapshot.child("message").getValue(String::class.java) ?: return
                val ts = snapshot.child("timestamp").getValue(Long::class.java) ?: return

                val msg = ChatMessage(msgId, senderId, senderName, text, ts,
                    if (senderId == myId) "sent" else "read")

                messages.add(msg)
                messages.sortBy { it.timestamp }
                adapter.submitList(messages.toList())
                recyclerMessages.scrollToPosition(messages.size - 1)
                persistMessages()

                // Mark read
                if (senderId != myId) {
                    snapshot.ref.child("status").setValue("read")
                    getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                        .edit().putLong("read_time_$chatId", System.currentTimeMillis()).apply()
                }
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {
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

        threadRef!!.orderByChild("timestamp")
            .addChildEventListener(threadListener!!)
    }

    private fun persistMessages() {
        val allChats = ChatStorage.getSavedChats(this).toMutableList()
        val idx = allChats.indexOfFirst { it.id == chatId }
        if (idx >= 0) {
            allChats[idx] = allChats[idx].copy(messages = messages.toList())
            ChatStorage.persistChats(this, allChats)
        }
    }

    private fun loadLastActive() {
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId }?.senderId ?: return
        if (!AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("lastActive")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        tvToolbarSubtitle.text = formatLastActive(s.getValue(Long::class.java))
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })
        }
    }

    private fun loadToolbarAvatar() {
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId }?.senderId ?: return
        if (!AuthActivity.TEST_MODE) {
            val iv = findViewById<CircleImageView>(R.id.ivToolbarAvatar)
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("avatar")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val data = s.getValue(String::class.java) ?: return
                        try {
                            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                            iv.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        } catch (_: Exception) {}
                    }
                    override fun onCancelled(e: DatabaseError) {}
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
                            ChatStorage.deleteChat(this, chatId); finish()
                        }.setNegativeButton("Cancel", null).show()
                    true
                }
                R.id.action_block -> {
                    AlertDialog.Builder(this)
                        .setTitle("Block this user?")
                        .setMessage("You won't be matched with them again.")
                        .setPositiveButton("Block") { _, _ ->
                            val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                            val blocked = prefs.getStringSet("blocked_users", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                            chat.partnerAccountId?.let { blocked.add(it) }
                            prefs.edit().putStringSet("blocked_users", blocked).apply()
                            ChatStorage.deleteChat(this, chatId)
                            Toast.makeText(this, "User blocked", Toast.LENGTH_SHORT).show()
                            finish()
                        }.setNegativeButton("Cancel", null).show()
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

    private fun showPartnerProfileDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_partner_profile, null)
        val ivAvatar = dialogView.findViewById<CircleImageView>(R.id.ivPartnerAvatar)
        val tvName = dialogView.findViewById<TextView>(R.id.tvPartnerName)
        val tvGender = dialogView.findViewById<TextView>(R.id.tvPartnerGender)
        val tvAge = dialogView.findViewById<TextView>(R.id.tvPartnerAge)
        val tvCity = dialogView.findViewById<TextView>(R.id.tvPartnerCity)

        tvName.text = chat.partnerName.ifEmpty { "AnnoUser" }
        tvGender.text = chat.partnerGender ?: "Not specified"
        tvAge.text = if (chat.partnerAge != null) chat.partnerAge.toString() else "Not specified"
        tvCity.text = chat.partnerCity ?: "Not specified"

        ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        when (chat.partnerGender) {
            "Female" -> ivAvatar.borderColor = android.graphics.Color.parseColor("#E91E63")
            else -> ivAvatar.borderColor = resources.getColor(R.color.primary, theme)
        }

        var fetchedAvatar: String? = null
        val partnerUid = chat.partnerAccountId
        if (partnerUid != null && !AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("avatar")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        val data = s.getValue(String::class.java) ?: return
                        fetchedAvatar = data
                        try {
                            val bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT)
                            ivAvatar.setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                        } catch (_: Exception) {}
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })

            // Also load fresh profile data
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("profile")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        tvName.text = s.child("displayName").getValue(String::class.java) ?: "AnnoUser"
                        tvGender.text = s.child("gender").getValue(String::class.java) ?: "Not specified"
                        val age = s.child("age").getValue(Long::class.java)
                        tvAge.text = if (age != null) age.toString() else "Not specified"
                        tvCity.text = s.child("city").getValue(String::class.java) ?: "Not specified"
                    }
                    override fun onCancelled(e: DatabaseError) {}
                })
        }

        ivAvatar.setOnClickListener {
            val base64 = fetchedAvatar
            if (base64 != null) {
                startActivity(Intent(this, PhotoViewActivity::class.java).apply {
                    putExtra(PhotoViewActivity.EXTRA_IMAGE_BASE64, base64)
                })
            } else {
                Toast.makeText(this, "No profile picture available", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(this).setView(dialogView).setPositiveButton("Close", null).show()
    }

    private fun formatLastActive(timestamp: Long?): String {
        if (timestamp == null || timestamp == 0L) return "last seen recently"
        val diff = System.currentTimeMillis() - timestamp
        val minutes = (diff / 60000).toInt()
        if (minutes < 1) return "Active now"
        if (minutes < 60) return "last seen $minutes min ago"
        val hours = minutes / 60
        if (hours < 24) return "last seen ${hours}h ago"
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        return if (cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR))
            "last seen yesterday"
        else "last seen ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}"
    }
}
