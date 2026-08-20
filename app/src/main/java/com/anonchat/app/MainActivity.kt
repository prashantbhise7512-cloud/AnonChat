package com.anonchat.app

import android.content.Intent
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

import android.content.Context

class MainActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
    }

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

    private val photoPickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { processAndSendPhoto(it) }
    }

    private val cameraLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicturePreview()
    ) { bitmap: android.graphics.Bitmap? ->
        bitmap?.let { processAndSendPhotoBitmap(it) }
    }

    private fun showPhotoSourceDialog() {
        if (currentSessionId == null) {
            Toast.makeText(this, "Connect to a chat room to send photos", Toast.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf("📷 Take Photo (Camera)", "🖼️ Choose from Gallery")
        AlertDialog.Builder(this)
            .setTitle("Attach Photo")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> cameraLauncher.launch(null)
                    1 -> photoPickerLauncher.launch("image/*")
                }
            }
            .show()
    }

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: MessageAdapter

    // Views
    private lateinit var headerContainer: View
    private lateinit var tvMainTitle: TextView
    private lateinit var ivMainPartnerAvatar: de.hdodenhof.circleimageview.CircleImageView
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
        ThemeManager.applyTheme(this)
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
        headerContainer = findViewById(R.id.toolbar)
        tvMainTitle = findViewById(R.id.tvMainTitle)
        ivMainPartnerAvatar = findViewById(R.id.ivMainPartnerAvatar)
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
        headerContainer.setOnClickListener {
            val partnerId = currentPartnerId
            if (!partnerId.isNullOrEmpty()) {
                val intent = Intent(this, PartnerProfileActivity::class.java).apply {
                    putExtra(PartnerProfileActivity.EXTRA_PARTNER_ACCOUNT_ID, partnerId)
                    putExtra(PartnerProfileActivity.EXTRA_PARTNER_NAME, currentPartnerName ?: "AnnoUser")
                }
                startActivity(intent)
            }
        }
    }

    private var replyingMessage: ChatMessage? = null

    private fun setupRecyclerView() {
        adapter = MessageAdapter(userId) { selectedMsg ->
            showMessageOptionsDialog(selectedMsg)
        }
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerMessages.adapter = adapter
    }

    private fun showMessageOptionsDialog(message: ChatMessage) {
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_message_options, null)

        val tvOptionsSender = view.findViewById<TextView>(R.id.tvOptionsSender)
        val tvOptionsSnippet = view.findViewById<TextView>(R.id.tvOptionsSnippet)
        val optionReply = view.findViewById<LinearLayout>(R.id.optionReply)
        val optionEdit = view.findViewById<LinearLayout>(R.id.optionEdit)
        val optionDelete = view.findViewById<LinearLayout>(R.id.optionDelete)

        val senderName = if (message.senderId == userId) "You" else (currentPartnerName ?: "Stranger")
        val snippet = when (message.type) {
            "voice" -> "🎤 Voice message"
            "photo" -> "📷 Photo"
            else -> message.message
        }

        tvOptionsSender.text = senderName
        tvOptionsSnippet.text = snippet

        optionReply.setOnClickListener {
            bottomSheetDialog.dismiss()
            setReplyMessage(message)
        }

        val canEdit = (message.senderId == userId) && (message.type == "text") && !message.isDeleted
        if (canEdit) {
            optionEdit.visibility = View.VISIBLE
            optionEdit.setOnClickListener {
                bottomSheetDialog.dismiss()
                showEditMessageDialog(message)
            }
        } else {
            optionEdit.visibility = View.GONE
        }

        if (!message.isDeleted) {
            optionDelete.visibility = View.VISIBLE
            optionDelete.setOnClickListener {
                bottomSheetDialog.dismiss()
                confirmDeleteMessage(message)
            }
        } else {
            optionDelete.visibility = View.GONE
        }

        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }

    private fun showEditMessageDialog(message: ChatMessage) {
        val input = EditText(this).apply {
            setText(message.message)
            setSelection(text.length)
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty() && newText != message.message) {
                    performEditMessage(message, newText)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performEditMessage(message: ChatMessage, newText: String) {
        val idx = messages.indexOfFirst { it.id == message.id }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(message = newText, isEdited = true)
            adapter.submitList(messages.toList())
        }

        val sessionId = currentSessionId
        if (sessionId != null) {
            val ref = sessionsRef.child(sessionId).child("messages")
            ref.orderByChild("id").equalTo(message.id).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.child("message").setValue(newText)
                        child.ref.child("isEdited").setValue(true)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }

        activeThreadId?.let { threadId ->
            val ref = FirebaseDatabase.getInstance().reference.child("threads").child(threadId).child("messages")
            ref.orderByChild("id").equalTo(message.id).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.child("message").setValue(newText)
                        child.ref.child("isEdited").setValue(true)
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    private fun confirmDeleteMessage(message: ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle("Delete Message")
            .setMessage("Are you sure you want to delete this message?")
            .setPositiveButton("Delete") { _, _ ->
                performDeleteMessage(message)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDeleteMessage(message: ChatMessage) {
        val idx = messages.indexOfFirst { it.id == message.id }
        if (idx >= 0) {
            messages[idx] = messages[idx].copy(
                isDeleted = true,
                message = "🚫 This message was deleted",
                audioData = null
            )
            adapter.submitList(messages.toList())
        }

        val sessionId = currentSessionId
        if (sessionId != null) {
            val ref = sessionsRef.child(sessionId).child("messages")
            ref.orderByChild("id").equalTo(message.id).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.child("isDeleted").setValue(true)
                        child.ref.child("message").setValue("🚫 This message was deleted")
                        child.ref.child("audioData").setValue("")
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }

        activeThreadId?.let { threadId ->
            val ref = FirebaseDatabase.getInstance().reference.child("threads").child(threadId).child("messages")
            ref.orderByChild("id").equalTo(message.id).addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        child.ref.child("isDeleted").setValue(true)
                        child.ref.child("message").setValue("🚫 This message was deleted")
                        child.ref.child("audioData").setValue("")
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
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
        findViewById<ImageView>(R.id.btnCloseReplyPreview)?.setOnClickListener {
            clearReplyMessage()
        }
        findViewById<ImageView>(R.id.btnAttachPhoto)?.setOnClickListener {
            showPhotoSourceDialog()
        }
    }

    private fun processAndSendPhoto(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val originalBitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return

            processAndSendPhotoBitmap(originalBitmap)
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to load photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processAndSendPhotoBitmap(originalBitmap: android.graphics.Bitmap) {
        try {
            val maxDimension = 1024
            val width = originalBitmap.width
            val height = originalBitmap.height
            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val scale = maxDimension.toFloat() / Math.max(width, height)
                val newWidth = (width * scale).toInt()
                val newHeight = (height * scale).toInt()
                android.graphics.Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
            } else {
                originalBitmap
            }

            val baos = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
            val imageBytes = baos.toByteArray()
            val base64Image = android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT)

            sendPhotoMessage(base64Image)
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to process photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendPhotoMessage(imageDataBase64: String) {
        val sid = currentSessionId ?: run {
            Toast.makeText(this, "Connect to a chat room to send photos", Toast.LENGTH_SHORT).show()
            return
        }
        val reply = replyingMessage
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = userId,
            senderName = userName,
            message = "📷 Photo",
            timestamp = System.currentTimeMillis(),
            status = "sent",
            type = "photo",
            imageData = imageDataBase64,
            replyToId = reply?.id,
            replyToSender = reply?.let { if (it.senderId == userId) "You" else (currentPartnerName ?: "Stranger") },
            replyToText = reply?.let { if (it.type == "voice") "🎤 Voice message" else if (it.type == "photo") "📷 Photo" else it.message }
        )

        messages.add(msg)
        adapter.submitList(messages.toList())
        recyclerMessages.scrollToPosition(messages.size - 1)
        clearReplyMessage()

        val payload = mutableMapOf<String, Any>(
            "id" to msg.id,
            "senderId" to msg.senderId,
            "senderName" to msg.senderName,
            "message" to msg.message,
            "timestamp" to msg.timestamp,
            "status" to "sent",
            "type" to "photo",
            "imageData" to imageDataBase64
        )
        msg.replyToId?.let { payload["replyToId"] = it }
        msg.replyToSender?.let { payload["replyToSender"] = it }
        msg.replyToText?.let { payload["replyToText"] = it }

        database.child("sessions").child(sid).child("messages").push().setValue(payload)
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
                messages.sortBy { it.timestamp }
                adapter.submitList(messages.toList())
                recyclerMessages.scrollToPosition(messages.size - 1)
                updateEmptyState()
            }
            override fun onChildChanged(s: DataSnapshot, p: String?) {
                val msgId = s.child("id").getValue(String::class.java) ?: return
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx >= 0) {
                    val text = s.child("message").getValue(String::class.java) ?: messages[idx].message
                    val isEdited = s.child("isEdited").getValue(Boolean::class.java) ?: messages[idx].isEdited
                    val isDeleted = s.child("isDeleted").getValue(Boolean::class.java) ?: messages[idx].isDeleted

                    messages[idx] = messages[idx].copy(
                        message = text,
                        isEdited = isEdited,
                        isDeleted = isDeleted
                    )
                    adapter.submitList(messages.toList())
                }
            }
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
                    listenForThreadMessages(threadId)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
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
                            val partnerId = savers.first { it != userId }
                            val threadId = listOf(userId, partnerId).sorted().joinToString("_")
                            val existing = ChatStorage.getSavedChats(this@MainActivity)
                            val alreadySaved = existing.any { it.threadId == threadId }
                            if (!alreadySaved) {
                                triggerSave()
                            }
                            addSystemMessageToChat("\u2705 Chat saved by both users")
                        }
                        partnerSaved && !iSaved -> {
                            addSystemMessageToChat("\uD83D\uDCBE Partner has saved chat", green = true)
                            // Re-show save button so this user can confirm
                            btnSaveChat.visibility = View.VISIBLE
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
        val partnerId = currentPartnerId
            ?: messages.firstOrNull { it.senderId != userId && it.senderId != "system" }?.senderId
        val partnerName = currentPartnerName ?: "AnnoUser"

        if (partnerId == null) {
            doSaveChat(partnerName, null, null, null, null)
            return
        }

        val cachedGender = TestSession.cachedProfileGender(this, partnerId)
        val cachedAge = TestSession.cachedProfileAge(this, partnerId)
        val cachedCity = TestSession.cachedProfileCity(this, partnerId)
        val cachedAvatar = TestSession.cachedProfileAvatar(this, partnerId)
            ?: getSharedPreferences("anonchat_prefs", MODE_PRIVATE).getString("avatar_$partnerId", null)

        if (!AuthActivity.TEST_MODE) {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            db.reference.child("users").child(partnerId)
                .addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
                    override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                        val profileSnapshot = snapshot.child("profile")
                        val displayName = profileSnapshot.child("displayName").getValue(String::class.java)
                            ?: snapshot.child("displayName").getValue(String::class.java)
                            ?: partnerName

                        val gender = profileSnapshot.child("gender").getValue(String::class.java)
                            ?: snapshot.child("gender").getValue(String::class.java)
                            ?: cachedGender

                        val rawAge = profileSnapshot.child("age").value ?: snapshot.child("age").value
                        val age = rawAge?.toString()?.toIntOrNull() ?: cachedAge

                        val city = profileSnapshot.child("city").getValue(String::class.java)
                            ?: snapshot.child("city").getValue(String::class.java)
                            ?: cachedCity

                        val avatar = snapshot.child("avatar").getValue(String::class.java)
                            ?: profileSnapshot.child("avatar").getValue(String::class.java)
                            ?: cachedAvatar

                        doSaveChat(displayName, gender, age, city, avatar)
                    }
                    override fun onCancelled(e: com.google.firebase.database.DatabaseError) {
                        doSaveChat(partnerName, cachedGender, cachedAge, cachedCity, cachedAvatar)
                    }
                })
        } else {
            doSaveChat(partnerName, cachedGender, cachedAge, cachedCity, cachedAvatar)
        }
    }

    private fun setReplyMessage(message: ChatMessage) {
        replyingMessage = message
        val layoutReplyPreview = findViewById<View>(R.id.layoutReplyPreview) ?: return
        val tvReplyingToSender = findViewById<TextView>(R.id.tvReplyingToSender) ?: return
        val tvReplyingToText = findViewById<TextView>(R.id.tvReplyingToText) ?: return

        val senderName = if (message.senderId == userId) "You" else (currentPartnerName ?: "Stranger")
        val snippet = if (message.type == "voice") "🎤 Voice message" else message.message

        tvReplyingToSender.text = "Replying to $senderName"
        tvReplyingToText.text = snippet
        layoutReplyPreview.visibility = View.VISIBLE

        editMessage.requestFocus()
    }

    private fun clearReplyMessage() {
        replyingMessage = null
        findViewById<View>(R.id.layoutReplyPreview)?.visibility = View.GONE
    }

    private fun sendMessage() {
        val text = editMessage.text?.toString()?.trim() ?: return
        if (text.isEmpty() || currentSessionId == null) return

        val reply = replyingMessage
        val msgRef = sessionsRef.child(currentSessionId!!).child("messages").push()
        val chatMessage = mutableMapOf<String, Any>(
            "id" to (msgRef.key ?: ""),
            "senderId" to userId,
            "senderName" to userName,
            "message" to text,
            "timestamp" to ServerValue.TIMESTAMP,
            "type" to "text"
        )
        reply?.let {
            chatMessage["replyToId"] = it.id
            chatMessage["replyToSender"] = if (it.senderId == userId) "You" else (currentPartnerName ?: "Stranger")
            chatMessage["replyToText"] = if (it.type == "voice") "🎤 Voice message" else it.message
        }

        msgRef.setValue(chatMessage)

        activeThreadId?.let { threadId ->
            UserDatabase.registerUserThread(userId, currentPartnerId, threadId)
            FirebaseDatabase.getInstance().reference
                .child("threads").child(threadId).child("messages")
                .push().setValue(chatMessage)
        }

        editMessage.text?.clear()
        clearReplyMessage()
    }

    // === UI STATES ===
    private fun showSearchingState() {
        tvMainTitle.text = getString(R.string.searching)
        ivMainPartnerAvatar.visibility = View.GONE
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
        tvMainTitle.text = partnerName
        ivMainPartnerAvatar.visibility = View.VISIBLE
        ivMainPartnerAvatar.setImageResource(R.drawable.ic_default_avatar)

        val partnerId = currentPartnerId
        if (!partnerId.isNullOrBlank()) {
            val cachedAvatar = TestSession.cachedProfileAvatar(this, partnerId)
                ?: getSharedPreferences("anonchat_prefs", MODE_PRIVATE).getString("avatar_$partnerId", null)
            if (!cachedAvatar.isNullOrEmpty()) {
                try {
                    val bytes = android.util.Base64.decode(cachedAvatar, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) ivMainPartnerAvatar.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            }

            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerId)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val profileSnap = snapshot.child("profile")
                        val dbName = profileSnap.child("displayName").getValue(String::class.java)
                            ?: snapshot.child("displayName").getValue(String::class.java)
                        if (!dbName.isNullOrEmpty()) {
                            currentPartnerName = dbName
                            tvMainTitle.text = dbName
                        }

                        val avatarData = snapshot.child("avatar").getValue(String::class.java)
                            ?: profileSnap.child("avatar").getValue(String::class.java)
                        if (!avatarData.isNullOrEmpty()) {
                            try {
                                val bytes = android.util.Base64.decode(avatarData, android.util.Base64.DEFAULT)
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) ivMainPartnerAvatar.setImageBitmap(bitmap)
                            } catch (_: Exception) {}
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {}
                })
        }

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

        val existing = ChatStorage.getSavedChats(this)
        val existingChat = existing.find {
            (threadId != null && it.threadId == threadId) ||
            (partnerId != null && it.partnerAccountId == partnerId)
        }
        val chatId = existingChat?.id ?: UUID.randomUUID().toString()

        val savedChat = SavedChat(
            id = chatId,
            savedAt = System.currentTimeMillis(),
            userName = userName,
            partnerName = partnerName,
            partnerAccountId = partnerId,
            threadId = threadId,
            partnerGender = gender ?: existingChat?.partnerGender,
            partnerAge = age ?: existingChat?.partnerAge,
            partnerCity = city ?: existingChat?.partnerCity,
            partnerAvatar = avatar ?: existingChat?.partnerAvatar,
            messages = messages.toList()
        )
        ChatStorage.saveChat(this, savedChat)
        btnSaveChat.visibility = View.GONE

        // Create the thread in Firebase so both users can chat later
        if (threadId != null && !AuthActivity.TEST_MODE) {
            UserDatabase.registerUserThread(userId, partnerId, threadId)
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            db.reference.child("threads").child(threadId).child("users").setValue(
                mapOf(userId to true, partnerId to true)
            )
            // Copy existing messages to the thread so both sides have them
            messages.filter { it.senderId != "system" }.forEach { msg ->
                val payload = mutableMapOf<String, Any>(
                    "id" to msg.id,
                    "senderId" to msg.senderId,
                    "senderName" to msg.senderName,
                    "message" to msg.message,
                    "timestamp" to msg.timestamp,
                    "status" to "read",
                    "type" to msg.type
                )
                msg.audioData?.let { payload["audioData"] = it }
                if (msg.durationMs > 0) payload["durationMs"] = msg.durationMs
                msg.replyToId?.let { payload["replyToId"] = it }
                msg.replyToSender?.let { payload["replyToSender"] = it }
                msg.replyToText?.let { payload["replyToText"] = it }

                db.reference.child("threads").child(threadId).child("messages").push().setValue(payload)
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
                timestamp = snapshot.child("timestamp").getValue(Long::class.java) ?: 0L,
                type = snapshot.child("type").getValue(String::class.java) ?: "text",
                audioData = snapshot.child("audioData").getValue(String::class.java),
                durationMs = snapshot.child("durationMs").getValue(Long::class.java) ?: 0L,
                replyToId = snapshot.child("replyToId").getValue(String::class.java),
                replyToSender = snapshot.child("replyToSender").getValue(String::class.java),
                replyToText = snapshot.child("replyToText").getValue(String::class.java)
            )
        } catch (e: Exception) { null }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        val color = ThemeManager.getPrimaryColor(this)
        findViewById<View>(R.id.toolbar)?.setBackgroundColor(color)
        MessageNotificationService.isLiveChatActive = true
        activeThreadId?.let { MessageNotificationService.activeThreadId = it }
    }

    override fun onPause() {
        super.onPause()
        MessageNotificationService.isLiveChatActive = false
        MessageNotificationService.activeThreadId = null
    }

    override fun onDestroy() {
        super.onDestroy()
        MessageNotificationService.isLiveChatActive = false
        MessageNotificationService.activeThreadId = null
        disconnectFromCurrent()
    }
}
