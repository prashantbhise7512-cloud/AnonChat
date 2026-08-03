package com.anonchat.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.google.android.material.appbar.MaterialToolbar
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_chat)

        chatId = intent.getStringExtra("CHAT_ID") ?: run { finish(); return }

        // Mark this chat as read
        val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
        prefs.edit().putLong("read_time_$chatId", System.currentTimeMillis()).apply()

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        val btnOverflowMenu = findViewById<ImageView>(R.id.btnOverflowMenu)
        val recyclerMessages = findViewById<RecyclerView>(R.id.recyclerMessages)
        val editMessage = findViewById<EditText>(R.id.editMessage)
        val btnSend = findViewById<FrameLayout>(R.id.btnSend)

        // Load saved chat
        val chats = ChatStorage.getSavedChats(this)
        val chat = chats.find { it.id == chatId } ?: run { finish(); return }

        val partnerName = chat.messages.firstOrNull { it.senderName != chat.userName }?.senderName ?: "AnnoUser"
        toolbar.title = "   $partnerName"
        toolbar.subtitle = "   last seen..."
        toolbar.setNavigationOnClickListener { finish() }

        // Fetch partner's last active time
        val partnerUid = chat.partnerAccountId
        if (partnerUid != null && !AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("lastActive")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val ts = snapshot.getValue(Long::class.java)
                        toolbar.subtitle = "   ${formatLastActive(ts)}"
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        } else if (partnerUid != null) {
            // Test mode: check local prefs
            val localTs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                .getLong("last_active_$partnerUid", 0L)
            if (localTs > 0) toolbar.subtitle = "   ${formatLastActive(localTs)}"
        }

        // Load partner avatar in toolbar
        val ivToolbarAvatar = findViewById<CircleImageView>(R.id.ivToolbarAvatar)
        val partnerUid = chat.partnerAccountId
        if (partnerUid != null && !AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("avatar")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val avatarData = snapshot.getValue(String::class.java) ?: return
                        try {
                            val bytes = android.util.Base64.decode(avatarData, android.util.Base64.DEFAULT)
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ivToolbarAvatar.setImageBitmap(bitmap)
                        } catch (_: Exception) {}
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }

        // Tapping the toolbar title shows the partner's profile card
        toolbar.setOnClickListener {
            showPartnerProfileDialog(chat)
        }

        // Find my userId in this chat
        val myId = chat.messages.firstOrNull { it.senderName == chat.userName }?.senderId ?: ""

        // Setup messages
        messages.addAll(chat.messages)
        adapter = MessageAdapter(myId)
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerMessages.adapter = adapter
        adapter.submitList(messages.toList())

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
                timestamp = System.currentTimeMillis()
            )

            messages.add(msg)
            adapter.submitList(messages.toList())
            recyclerMessages.scrollToPosition(messages.size - 1)

            // Persist to storage
            val allChats = ChatStorage.getSavedChats(this).toMutableList()
            val idx = allChats.indexOfFirst { it.id == chatId }
            if (idx >= 0) {
                val updated = allChats[idx].copy(messages = messages.toList())
                allChats[idx] = updated
                ChatStorage.persistChats(this, allChats)
                toolbar.subtitle = "${messages.size} messages"
            }

            editMessage.text?.clear()
        }

        // Overflow menu (three dots)
        btnOverflowMenu.setOnClickListener { view ->
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
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                    R.id.action_block -> {
                        AlertDialog.Builder(this)
                            .setTitle("Block this user?")
                            .setMessage("You won't be matched with them again.")
                            .setPositiveButton("Block") { _, _ ->
                                // Save blocked user id
                                val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                                val blocked = prefs.getStringSet("blocked_users", mutableSetOf()) ?: mutableSetOf()
                                val partnerUid = chat.partnerAccountId
                                if (partnerUid != null) {
                                    val updated = blocked.toMutableSet()
                                    updated.add(partnerUid)
                                    prefs.edit().putStringSet("blocked_users", updated).apply()
                                }
                                // Also delete the chat
                                ChatStorage.deleteChat(this, chatId)
                                Toast.makeText(this, "User blocked", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun showPartnerProfileDialog(chat: SavedChat) {
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

        // Default avatar tint based on gender
        ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        when (chat.partnerGender) {
            "Female" -> ivAvatar.borderColor = android.graphics.Color.parseColor("#E91E63")
            else -> ivAvatar.borderColor = resources.getColor(R.color.primary, theme)
        }

        // Fetch avatar from Firebase on demand
        var fetchedAvatarBase64: String? = null
        val partnerUid = chat.partnerAccountId
        if (partnerUid != null && !AuthActivity.TEST_MODE) {
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid).child("avatar")
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val avatarData = snapshot.getValue(String::class.java) ?: return
                        fetchedAvatarBase64 = avatarData
                        try {
                            val bytes = android.util.Base64.decode(avatarData, android.util.Base64.DEFAULT)
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ivAvatar.setImageBitmap(bitmap)
                        } catch (_: Exception) {}
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }

        // Tap avatar to view fullscreen (screenshot-protected)
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

    private fun formatLastActive(timestamp: Long?): String {
        if (timestamp == null || timestamp == 0L) return "last seen recently"
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val minutes = (diff / 60000).toInt()
        if (minutes < 1) return "Active now"
        if (minutes < 60) return "last seen $minutes min ago"
        val hours = minutes / 60
        if (hours < 24) return "last seen ${hours}h ago"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        return when {
            cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) ->
                "last seen yesterday"
            else -> {
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                "last seen ${sdf.format(java.util.Date(timestamp))}"
            }
        }
    }
}
