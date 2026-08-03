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
        adapter = SavedChatAdapter(emptyList()) { chat ->
            val intent = Intent(this, SavedChatActivity::class.java).apply {
                putExtra("CHAT_ID", chat.id)
            }
            startActivity(intent)
        }
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
    }

    override fun onResume() {
        super.onResume()
        // Re-select chats tab when returning from profile
        bottomNav.selectedItemId = R.id.nav_chats
        loadSavedChats()
        loadDisplayName()
        updateLastActive()
    }

    private fun showChatsTab() {
        tabChatsContent.visibility = View.VISIBLE
        tabProfileContent.visibility = View.GONE
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
                displayName = name ?: "AnnoUser"
                tvListIdentity.text = displayName
            }

            override fun onCancelled(error: DatabaseError) {
                displayName = TestSession.cachedDisplayName(this@ChatListActivity, uid) ?: "AnnoUser"
                tvListIdentity.text = displayName
            }
        })
    }

    private fun loadSavedChats() {
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
}
