package com.anonchat.app

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.adapter.SavedChatAdapter
import com.google.android.material.appbar.MaterialToolbar
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

    private val database by lazy { FirebaseDatabase.getInstance() }

    private var displayName: String = "Anonymous"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat_list)

        toolbar = findViewById(R.id.toolbar)
        tvListIdentity = findViewById(R.id.tvListIdentity)
        recyclerSavedChats = findViewById(R.id.recyclerSavedChats)
        emptyState = findViewById(R.id.emptyState)
        val btnJoinRoom = findViewById<MaterialButton>(R.id.btnJoinRoom)

        // Set up toolbar with overflow menu
        setSupportActionBar(toolbar)

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

        // Load display name from Firebase profile
        loadDisplayName()
    }

    override fun onResume() {
        super.onResume()
        loadSavedChats()
        loadDisplayName()
        updateLastActive()
    }

    private fun updateLastActive() {
        val uid = TestSession.currentUserId(this) ?: return
        val now = System.currentTimeMillis()
        // Save locally
        getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
            .edit().putLong("last_active_$uid", now).apply()
        // Save to Firebase
        if (!AuthActivity.TEST_MODE) {
            database.reference.child("users").child(uid).child("lastActive").setValue(now)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_chat_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_profile -> {
                val intent = Intent(this, ProfileActivity::class.java)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadDisplayName() {
        val uid = TestSession.currentUserId(this) ?: return

        // Locally cached name shows immediately (and is the only source in test mode
        // when the database is unreachable).
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
                // Fallback to cached value, then default
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
