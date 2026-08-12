package com.anonchat.app

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.*
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val userId: String by lazy {
        TestSession.currentUserId(this) ?: TestSession.uid(this) ?: UUID.randomUUID().toString()
    }
    private val userName: String by lazy {
        intent.getStringExtra("USER_NAME") ?: TestSession.cachedDisplayName(this, userId) ?: "Anonymous"
    }

    private val database by lazy { FirebaseDatabase.getInstance().reference }
    private val queueRef by lazy { database.child("queue") }
    private val sessionsRef by lazy { database.child("sessions") }

    private var currentSessionId: String? = null
    private var currentPartnerId: String? = null
    private var currentPartnerName: String? = null
    private var sessionMessagesListener: ChildEventListener? = null
    private var partnerPresenceListener: ValueEventListener? = null
    private var threadMessagesListener: ChildEventListener? = null
    private var matchListenerUser1: ChildEventListener? = null
    private var matchListenerUser2: ChildEventListener? = null
    private var activeThreadId: String? = null

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: MessageAdapter

    // Views
    private lateinit var toolbar: MaterialToolbar
    private lateinit var onlineIndicator: LinearLayout
    private lateinit var tvOnlineCount: TextView
    private lateinit var tvIdentity: TextView
    private lateinit var emptyState: LinearLayout
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptySubtitle: TextView
    private lateinit var recyclerMessages: RecyclerView
    private lateinit var editMessage: EditText
    private lateinit var btnSend: FrameLayout
    private lateinit var btnSaveChat: MaterialButton
    private lateinit var btnNewChat: MaterialButton
    private lateinit var btnLeaveChat: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupInput()
        setupActionButtons()
        startSearching()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        onlineIndicator = findViewById(R.id.onlineIndicator)
        tvOnlineCount = findViewById(R.id.tvOnlineCount)
        tvIdentity = findViewById(R.id.tvIdentity)
        emptyState = findViewById(R.id.emptyState)
        recyclerMessages = findViewById(R.id.recyclerMessages)
        editMessage = findViewById(R.id.editMessage)
        btnSend = findViewById(R.id.btnSend)
        btnSaveChat = findViewById(R.id.btnSaveChat)
        btnNewChat = findViewById(R.id.btnNewChat)
        btnLeaveChat = findViewById(R.id.btnLeaveChat)
    }

    private fun setupToolbar() {
        tvIdentity.text = getString(R.string.your_identity, userName)
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter(userId)
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerMessages.adapter = adapter
    }

    private fun setupInput() {
        editMessage.isEnabled = false
        btnSend.alpha = 0.5f
        btnSend.setOnClickListener { sendMessage() }
        editMessage.addTextChangedListener { text ->
            btnSend.alpha = if (text.isNullOrBlank() || currentSessionId == null) 0.5f else 1.0f
        }
        editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
    }

    private fun setupActionButtons() {
        btnSaveChat.setOnClickListener { saveChat() }
        btnNewChat.setOnClickListener { newChat() }
        btnLeaveChat.setOnClickListener { leaveChat() }
    }

    // === MATCHING SYSTEM ===
    private fun startSearching() {
        showSearchingState()
        messages.clear()
        adapter.submitList(emptyList())
        currentSessionId = null
        currentPartnerId = null
        currentPartnerName = null
        activeThreadId = null
        editMessage.isEnabled = false

        // Atomically check-and-claim a waiting partner (or add self to the queue) in a single
        // transaction. This prevents the race where two devices both write themselves into the
        // queue around the same time and then independently pick each other as a match, ending
        // up in two separate session nodes instead of the same shared one.
        var matchedPartnerId: String? = null
        var matchedPartnerName: String? = null

        queueRef.runTransaction(object : Transaction.Handler {
            override fun doTransaction(currentData: MutableData): Transaction.Result {
                // Reset on every attempt — this block can run multiple times if Firebase
                // has to retry the transaction due to a conflicting concurrent write.
                matchedPartnerId = null
                matchedPartnerName = null

                val waitingChild = currentData.children.firstOrNull { it.key != userId }
                if (waitingChild != null) {
                    val partnerId = waitingChild.child("userId").getValue(String::class.java)
                    val partnerName = waitingChild.child("userName").getValue(String::class.java) ?: "Stranger"
                    if (partnerId != null) {
                        matchedPartnerId = partnerId
                        matchedPartnerName = partnerName
                        // Claim them by removing their queue entry, atomically, right now.
                        currentData.child(waitingChild.key!!).value = null
                        return Transaction.success(currentData)
                    }
                }

                // No one waiting — add self to the queue.
                currentData.child(userId).child("userId").value = userId
                currentData.child(userId).child("userName").value = userName
                currentData.child(userId).child("joinedAt").value = System.currentTimeMillis()
                return Transaction.success(currentData)
            }

            override fun onComplete(error: DatabaseError?, committed: Boolean, snapshot: DataSnapshot?) {
                if (error != null || !committed) {
                    // TEMP DEBUG: surface why the matching transaction didn't commit.
                    android.util.Log.e("AnonChatMatch", "Queue transaction failed: committed=$committed error=${error?.message}", error?.toException())
                    // Something went wrong claiming/joining the queue — fall back to listening
                    // for a session in case a partner matches with us from their side instead.
                    waitForMatch()
                    return
                }

                val partnerId = matchedPartnerId
                val partnerName = matchedPartnerName
                if (partnerId != null && partnerName != null) {
                    val sessionId = UUID.randomUUID().toString()
                    val threadId = listOf(userId, partnerId).sorted().joinToString("_")
                    val sessionData = mapOf(
                        "user1" to mapOf("userId" to userId, "userName" to userName),
                        "user2" to mapOf("userId" to partnerId, "userName" to partnerName),
                        "threadId" to threadId,
                        "createdAt" to ServerValue.TIMESTAMP,
                        "active" to true
                    )
                    sessionsRef.child(sessionId).setValue(sessionData)
                    connectToSession(sessionId, partnerId, partnerName)
                } else {
                    // We're the one waiting now. If this device disconnects (app killed, network
                    // lost, crash) before we explicitly leave the queue, have the Firebase server
                    // remove our entry automatically — so nobody can match with a ghost entry
                    // that isn't really there anymore.
                    queueRef.child(userId).onDisconnect().removeValue()
                    waitForMatch()
                }
            }
        })
    }

    private fun waitForMatch() {
        cleanupMatchListeners()

        val listenerUser1 = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                handleIncomingMatch(snapshot)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }

        val listenerUser2 = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                handleIncomingMatch(snapshot)
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }

        matchListenerUser1 = listenerUser1
        sessionsRef.orderByChild("user1/userId").equalTo(userId)
            .addChildEventListener(listenerUser1)

        matchListenerUser2 = listenerUser2
        sessionsRef.orderByChild("user2/userId").equalTo(userId)
            .addChildEventListener(listenerUser2)
    }

    private fun handleIncomingMatch(snapshot: DataSnapshot) {
        val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
        if (!active) return
        val sessionId = snapshot.key ?: return
        if (currentSessionId != null) return

        val isUser1 = snapshot.child("user1/userId").getValue(String::class.java) == userId
        val partnerId = if (isUser1) {
            snapshot.child("user2/userId").getValue(String::class.java)
        } else {
            snapshot.child("user1/userId").getValue(String::class.java)
        }
        val partnerName = if (isUser1) {
            snapshot.child("user2/userName").getValue(String::class.java)
        } else {
            snapshot.child("user1/userName").getValue(String::class.java)
        } ?: "Stranger"

        if (partnerId != null) {
            connectToSession(sessionId, partnerId, partnerName)
        }
    }

    private fun cleanupMatchListeners() {
        matchListenerUser1?.let {
            sessionsRef.orderByChild("user1/userId").equalTo(userId).removeEventListener(it)
        }
        matchListenerUser1 = null
        matchListenerUser2?.let {
            sessionsRef.orderByChild("user2/userId").equalTo(userId).removeEventListener(it)
        }
        matchListenerUser2 = null
    }

    private fun connectToSession(sessionId: String, partnerId: String, partnerName: String) {
        // We're matched now — cancel the onDisconnect queue-cleanup registered while waiting,
        // since our queue entry is already gone (removed by whoever matched with us, or by us
        // matching them) and we don't want a stale disconnect handler lingering around.
        queueRef.child(userId).onDisconnect().cancel()
        cleanupMatchListeners()

        currentSessionId = sessionId
        currentPartnerId = partnerId
        currentPartnerName = partnerName
        showConnectedState(partnerName)
        editMessage.isEnabled = true
        editMessage.requestFocus()
        listenForSessionMessages(sessionId)
        listenForPartnerDisconnect(sessionId)
        listenForPartnerSave(sessionId)
        listenForThreadMessagesFromSession(sessionId)
    }

    // === MESSAGING ===
    private fun listenForSessionMessages(sessionId: String) {
        sessionMessagesListener?.let {
            sessionsRef.child(sessionId).child("messages").removeEventListener(it)
        }

        sessionMessagesListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val msg = snapshotToMessage(snapshot) ?: return
                messages.add(msg)
                adapter.submitList(messages.toList())
                recyclerMessages.scrollToPosition(messages.size - 1)
                updateEmptyState()
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }

        sessionsRef.child(sessionId).child("messages")
            .orderByChild("timestamp")
            .addChildEventListener(sessionMessagesListener!!)
    }

    private fun listenForPartnerDisconnect(sessionId: String) {
        partnerPresenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val active = snapshot.getValue(Boolean::class.java) ?: false
                if (!active && currentSessionId == sessionId) {
                    showDisconnectedState()
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        sessionsRef.child(sessionId).child("active")
            .addValueEventListener(partnerPresenceListener!!)
    }

    private fun listenForThreadMessagesFromSession(sessionId: String) {
        sessionsRef.child(sessionId).child("threadId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val threadId = snapshot.getValue(String::class.java)
                    setThreadDebug(threadId)
                    listenForThreadMessages(threadId)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun setThreadDebug(threadId: String?) {
        tvOnlineCount.text = if (threadId.isNullOrBlank()) {
            "Online"
        } else {
            "Online • thread: ${threadId.takeIf { it.length <= 40 } ?: threadId.take(40) + "..."}"
        }
    }

    private fun listenForThreadMessages(threadId: String?) {
        threadMessagesListener?.let { listener ->
            activeThreadId?.let { currentId ->
                FirebaseDatabase.getInstance().reference
                    .child("threads").child(currentId).child("messages")
                    .removeEventListener(listener)
            }
        }

        activeThreadId = threadId
        if (threadId == null) return

        val threadRef = FirebaseDatabase.getInstance().reference
            .child("threads").child(threadId).child("messages")

        threadMessagesListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prev: String?) {
                val msg = snapshotToMessage(snapshot) ?: return
                if (messages.any { it.id == msg.id }) return
                messages.add(msg)
                messages.sortBy { it.timestamp }
                adapter.submitList(messages.toList())
                recyclerMessages.scrollToPosition(messages.size - 1)
                updateEmptyState()
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {}
            override fun onChildRemoved(s: DataSnapshot) {}
            override fun onChildMoved(s: DataSnapshot, p: String?) {}
            override fun onCancelled(e: DatabaseError) {}
        }

        threadRef.addChildEventListener(threadMessagesListener!!)
    }

    private fun listenForPartnerSave(sessionId: String) {
        sessionsRef.child(sessionId).child("savedBy")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val savers = snapshot.children.mapNotNull { it.key }.filter { it.isNotEmpty() }
                    val iSaved = savers.contains(userId)
                    val partnerSaved = savers.any { it != userId }

                    when {
                        iSaved && partnerSaved -> {
                            // Both saved — save locally if not already done
                            val existing = ChatStorage.getSavedChats(this@MainActivity)
                            val threadId = listOf(userId, savers.first { it != userId }).sorted().joinToString("_")
                            val alreadySaved = existing.any { it.threadId == threadId }
                            if (!alreadySaved) {
                                triggerSave()
                                addSystemMessageToChat("\u2705 Chat saved by both users")
                            }
                        }
                        partnerSaved && !iSaved -> {
                            addSystemMessageToChat("\uD83D\uDCBE Partner has saved chat", green = true)
                        }
                    }
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun addSystemMessageToChat(text: String, green: Boolean = false) {
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = "system",
            senderName = "system",
            message = text,
            timestamp = System.currentTimeMillis(),
            status = if (green) "system_green" else "system"
        )
        messages.add(msg)
        adapter.submitList(messages.toList())
        recyclerMessages.scrollToPosition(messages.size - 1)
    }

    private fun triggerSave() {
        val partnerId = currentPartnerId ?: messages.firstOrNull { it.senderId != userId }?.senderId
        val partnerName = currentPartnerName ?: "AnonUser"
        if (partnerId != null && !AuthActivity.TEST_MODE) {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            db.reference.child("users").child(partnerId)
                .addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                        val profileSnapshot = snapshot.child("profile")
                        val displayName = profileSnapshot.child("displayName").getValue(String::class.java) ?: partnerName
                        val gender = profileSnapshot.child("gender").getValue(String::class.java)
                        val age = profileSnapshot.child("age").getValue(Long::class.java)?.toInt()
                        val city = profileSnapshot.child("city").getValue(String::class.java)
                        val avatar = snapshot.child("avatar").getValue(String::class.java)

                        doSaveChat(displayName, gender, age, city, avatar)
                    }
                    override fun onCancelled(e: com.google.firebase.database.DatabaseError) {
                        doSaveChat(partnerName, null, null, null, null)
                    }
                })
        } else {
            doSaveChat(partnerName, null, null, null, null)
        }
    }

    private fun sendMessage() {
        val text = editMessage.text?.toString()?.trim() ?: return
        if (text.isEmpty() || currentSessionId == null) return

        val msgRef = sessionsRef.child(currentSessionId!!).child("messages").push()
        val chatMessage = mapOf(
            "id" to (msgRef.key ?: ""),
            "senderId" to userId,
            "senderName" to userName,
            "message" to text,
            "timestamp" to ServerValue.TIMESTAMP
        )
        msgRef.setValue(chatMessage)

        activeThreadId?.let { threadId ->
            FirebaseDatabase.getInstance().reference
                .child("threads").child(threadId).child("messages")
                .push().setValue(chatMessage)
        }

        editMessage.text?.clear()
    }

    // === UI STATES ===
    private fun showSearchingState() {
        toolbar.title = getString(R.string.searching)
        onlineIndicator.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        recyclerMessages.visibility = View.GONE
        btnSaveChat.visibility = View.GONE
        btnNewChat.visibility = View.GONE

        // Update empty state text
        val titleView = emptyState.getChildAt(1) as? TextView
        val subtitleView = emptyState.getChildAt(2) as? TextView
        titleView?.text = ""
        subtitleView?.text = getString(R.string.searching_subtitle)
    }

    private fun showConnectedState(partnerName: String) {
        toolbar.title = partnerName
        onlineIndicator.visibility = View.VISIBLE
        tvOnlineCount.text = "Online"
        emptyState.visibility = View.GONE
        recyclerMessages.visibility = View.VISIBLE
        btnSaveChat.visibility = View.VISIBLE
        btnNewChat.visibility = View.GONE
    }

    private fun showDisconnectedState() {
        editMessage.isEnabled = false
        btnSaveChat.visibility = View.GONE
        btnNewChat.visibility = View.VISIBLE
        onlineIndicator.visibility = View.VISIBLE
        tvOnlineCount.text = "Disconnected"
        Toast.makeText(this, "User has left the chat", Toast.LENGTH_SHORT).show()
    }

    private fun updateEmptyState() {
        if (messages.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            recyclerMessages.visibility = View.GONE
        } else {
            emptyState.visibility = View.GONE
            recyclerMessages.visibility = View.VISIBLE
        }
    }

    // === ACTIONS ===
    private fun saveChat() {
        if (messages.isEmpty()) {
            Toast.makeText(this, R.string.save_chat_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (currentSessionId == null) {
            Toast.makeText(this, "Session has ended", Toast.LENGTH_SHORT).show()
            return
        }

        // Save locally immediately so the user can continue later.
        triggerSave()

        // Mark the chat as saved, but keep the temporary session alive so both users can
        // continue chatting in the same thread until one of them leaves.
        sessionsRef.child(currentSessionId!!).child("savedBy").child(userId).setValue(true)
        sessionsRef.child(currentSessionId!!).child("active").setValue(true)
        Toast.makeText(this, "Chat saved. You can continue later.", Toast.LENGTH_SHORT).show()
        btnSaveChat.visibility = View.GONE
    }

    private fun doSaveChat(partnerName: String, gender: String?, age: Int?, city: String?, avatar: String?) {
        val partnerId = currentPartnerId ?: messages.firstOrNull { it.senderId != userId }?.senderId

        // Stable thread id: sorted user ids joined — same on both devices
        val threadId = if (partnerId != null) {
            listOf(userId, partnerId).sorted().joinToString("_")
        } else null

        val savedChat = SavedChat(
            id = UUID.randomUUID().toString(),
            savedAt = System.currentTimeMillis(),
            userName = userName,
            partnerName = partnerName,
            partnerAccountId = partnerId,
            threadId = threadId,
            partnerGender = gender,
            partnerAge = age,
            partnerCity = city,
            partnerAvatar = avatar,
            messages = messages.toList()
        )
        ChatStorage.saveChat(this, savedChat)
        btnSaveChat.visibility = View.GONE

        // Create the thread in Firebase so both users can chat later
        if (threadId != null && !AuthActivity.TEST_MODE) {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            db.reference.child("threads").child(threadId).child("users").setValue(
                mapOf(userId to true, partnerId to true)
            )
            // Copy existing messages to the thread so both sides have them
            messages.filter { it.senderId != "system" }.forEach { msg ->
                db.reference.child("threads").child(threadId).child("messages").push().setValue(
                    mapOf(
                        "id" to msg.id,
                        "senderId" to msg.senderId,
                        "senderName" to msg.senderName,
                        "message" to msg.message,
                        "timestamp" to msg.timestamp,
                        "status" to "read"
                    )
                )
            }
        }
    }

    private fun newChat() {
        disconnectFromCurrent()
        messages.clear()
        adapter.submitList(emptyList())
        startSearching()
    }

    private fun leaveChat() {
        AlertDialog.Builder(this)
            .setTitle(R.string.leave_confirm_title)
            .setMessage(R.string.leave_confirm_message)
            .setPositiveButton("Leave") { _, _ ->
                disconnectFromCurrent()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disconnectFromCurrent() {
        val sessionId = currentSessionId
        if (sessionId != null) {
            sessionsRef.child(sessionId).child("active").setValue(false)
            sessionsRef.child(sessionId).removeValue()
        }

        sessionMessagesListener?.let { listener ->
            sessionId?.let { id ->
                sessionsRef.child(id).child("messages").removeEventListener(listener)
            }
        }
        partnerPresenceListener?.let { listener ->
            sessionId?.let { id ->
                sessionsRef.child(id).child("active").removeEventListener(listener)
            }
        }
        threadMessagesListener?.let { listener ->
            activeThreadId?.let { threadId ->
                FirebaseDatabase.getInstance().reference
                    .child("threads").child(threadId).child("messages")
                    .removeEventListener(listener)
            }
        }

        cleanupMatchListeners()

        // Remove from queue if still there, and cancel any pending onDisconnect cleanup for it
        queueRef.child(userId).onDisconnect().cancel()
        queueRef.child(userId).removeValue()
        currentSessionId = null
        currentPartnerId = null
        currentPartnerName = null
        activeThreadId = null
    }

    // === HELPERS ===
    private fun snapshotToMessage(snapshot: DataSnapshot): ChatMessage? {
        return try {
            ChatMessage(
                id = snapshot.child("id").getValue(String::class.java) ?: "",
                senderId = snapshot.child("senderId").getValue(String::class.java) ?: "",
                senderName = snapshot.child("senderName").getValue(String::class.java) ?: "",
                message = snapshot.child("message").getValue(String::class.java) ?: "",
                timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L
            )
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectFromCurrent()
    }
}
