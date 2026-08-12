package com.anonchat.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.adapter.SavedChatAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class ChatListActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var recyclerSavedChats: RecyclerView
    private lateinit var emptyState: LinearLayout
    private lateinit var adapter: SavedChatAdapter
    private lateinit var tvListIdentity: TextView
    private lateinit var tabChatsContent: LinearLayout
    private lateinit var tabProfileContent: NestedScrollView
    private lateinit var bottomNav: BottomNavigationView

    private val database by lazy { FirebaseDatabase.getInstance() }
    private var displayName: String = "Anonymous"
    private val threadListeners = mutableMapOf<String, com.google.firebase.database.ChildEventListener>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_list)

        toolbar = findViewById(R.id.toolbar)
        tvListIdentity = findViewById(R.id.tvListIdentity)
        recyclerSavedChats = findViewById(R.id.recyclerSavedChats)
        emptyState = findViewById(R.id.emptyState)
        tabChatsContent = findViewById(R.id.tabChatsContent)
        tabProfileContent = findViewById(R.id.tabProfileContent)
        bottomNav = findViewById(R.id.bottomNav)
        val btnJoinRoom = findViewById<MaterialButton>(R.id.btnJoinRoom)

        // Bottom navigation tab switching
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chats -> {
                    showChatsTab()
                    true
                }
                R.id.nav_profile -> {
                    openProfile()
                    false // don't highlight — profile is a separate activity
                }
                else -> false
            }
        }

        // Setup saved chats list
        adapter = SavedChatAdapter(emptyList(), { chat ->
            val intent = Intent(this, SavedChatActivity::class.java).apply {
                putExtra("CHAT_ID", chat.id)
            }
            startActivity(intent)
        }, { chat ->
            val intent = Intent(this, PartnerProfileActivity::class.java).apply {
                putExtra(PartnerProfileActivity.EXTRA_PARTNER_NAME, chat.partnerName)
                putExtra(PartnerProfileActivity.EXTRA_PARTNER_GENDER, chat.partnerGender)
                putExtra(PartnerProfileActivity.EXTRA_PARTNER_AGE, chat.partnerAge)
                putExtra(PartnerProfileActivity.EXTRA_PARTNER_CITY, chat.partnerCity)
                putExtra(PartnerProfileActivity.EXTRA_PARTNER_AVATAR_BASE64, chat.partnerAvatar)
                putExtra(PartnerProfileActivity.EXTRA_PARTNER_ACCOUNT_ID, chat.partnerAccountId)
            }
            startActivity(intent)
        })
        recyclerSavedChats.layoutManager = LinearLayoutManager(this)
        recyclerSavedChats.adapter = adapter

        // Enter chat room
        btnJoinRoom.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("USER_NAME", displayName)
            }
            startActivity(intent)
        }

        loadDisplayName()
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        // Re-select chats tab when returning from profile
        bottomNav.selectedItemId = R.id.nav_chats
        loadSavedChats()
        loadDisplayName()
        updateLastActive()
        startSavedChatListeners()
        // Start notification service for background message delivery
        MessageNotificationService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSavedChatListeners()
    }

    private fun showChatsTab() {
        tabChatsContent.visibility = View.VISIBLE
        tabProfileContent.visibility = View.GONE
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    private fun openProfile() {
        startActivity(Intent(this, ProfileActivity::class.java))
    }

    private fun updateLastActive() {
        val uid = TestSession.currentUserId(this) ?: return
        val now = System.currentTimeMillis()
        getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
            .edit().putLong("last_active_$uid", now).apply()
        if (!AuthActivity.TEST_MODE) {
            database.reference.child("users").child(uid).child("lastActive").setValue(now)
        }
    }

    private fun loadDisplayName() {
        val uid = TestSession.currentUserId(this) ?: return

        TestSession.cachedDisplayName(this, uid)?.let {
            displayName = it
            tvListIdentity.text = it
        }

        val profileRef = database.reference.child("users").child(uid).child("profile")
        profileRef.child("displayName").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val name = snapshot.getValue(String::class.java)
                    ?: TestSession.cachedDisplayName(this@ChatListActivity, uid)
                displayName = name ?: "AnonUser"
                tvListIdentity.text = displayName
            }

            override fun onCancelled(error: DatabaseError) {
                displayName = TestSession.cachedDisplayName(this@ChatListActivity, uid) ?: "AnonUser"
                tvListIdentity.text = displayName
            }
        })
    }

    private fun loadSavedChats() {
        ChatStorage.backfillThreadIds(this)

        val chats = ChatStorage.getSavedChats(this)
            .sortedByDescending { chat ->
                chat.messages.lastOrNull()?.timestamp ?: chat.savedAt
            }
        adapter.updateChats(chats)

        if (chats.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerSavedChats.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerSavedChats.visibility = View.VISIBLE
        }
    }

    private fun startSavedChatListeners() {
        stopSavedChatListeners()
        val chats = ChatStorage.getSavedChats(this)
        val currentUserId = TestSession.currentUserId(this) ?: return

        chats.forEach { chat ->
            val threadId = chat.threadId ?: deriveThreadId(chat)
            if (threadId.isNullOrBlank()) return@forEach
            if (threadListeners.containsKey(chat.id)) return@forEach

            val listener = object : com.google.firebase.database.ChildEventListener {
                override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                    val message = parseThreadMessage(snapshot) ?: return
                    if (message.senderId == currentUserId) return

                    val existingChat = ChatStorage.getSavedChats(this@ChatListActivity)
                        .find { it.id == chat.id } ?: return
                    if (existingChat.messages.any { it.id == message.id }) return

                    ChatStorage.appendMessageToChat(this@ChatListActivity, chat.id, message)
                    loadSavedChats()
                    if (message.senderId != currentUserId) {
                        android.widget.Toast.makeText(this@ChatListActivity, "New message received", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) {}
                override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {}
                override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
            }

            val threadRef = database.reference.child("threads").child(threadId).child("messages")
            threadRef.orderByChild("timestamp").addChildEventListener(listener)
            threadListeners[chat.id] = listener
        }
    }

    private fun stopSavedChatListeners() {
        val chats = ChatStorage.getSavedChats(this)
        chats.forEach { chat ->
            val listener = threadListeners[chat.id] ?: return@forEach
            database.reference.child("threads").child(chat.threadId ?: deriveThreadId(chat) ?: return@forEach)
                .child("messages").removeEventListener(listener)
        }
        threadListeners.clear()
    }

    private fun deriveThreadId(chat: com.anonchat.app.model.SavedChat): String? {
        val currentUid = TestSession.currentUserId(this) ?: return null
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != currentUid }?.senderId
        return partnerUid?.let { listOf(currentUid, it).sorted().joinToString("_") }
    }

    private fun parseThreadMessage(snapshot: com.google.firebase.database.DataSnapshot): com.anonchat.app.model.ChatMessage? {
        return try {
            com.anonchat.app.model.ChatMessage(
                id = snapshot.child("id").getValue(String::class.java) ?: return null,
                senderId = snapshot.child("senderId").getValue(String::class.java) ?: return null,
                senderName = snapshot.child("senderName").getValue(String::class.java) ?: return null,
                message = snapshot.child("message").getValue(String::class.java) ?: return null,
                timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L,
                status = snapshot.child("status").getValue(String::class.java) ?: "sent"
            )
        } catch (e: Exception) {
            null
        }
    }
}
