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

    private var replyingMessage: ChatMessage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_saved_chat)

        chatId = intent.getStringExtra("CHAT_ID") ?: run { finish(); return }

        // Tell the notification service we're viewing this chat — suppress notifications
        MessageNotificationService.activeChatId = chatId

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
        val btnCloseReplyPreview = findViewById<ImageView>(R.id.btnCloseReplyPreview)

        btnCloseReplyPreview.setOnClickListener {
            clearReplyMessage()
        }

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

        val swipeRefresh = findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.swipeRefreshSavedChat)
        swipeRefresh.setOnRefreshListener {
            val updatedChats = ChatStorage.getSavedChats(this)
            val updatedChat = updatedChats.find { it.id == chatId }
            if (updatedChat != null) {
                chat = updatedChat
                messages.clear()
                messages.addAll(updatedChat.messages)
                adapter.submitList(messages.toList())
                loadLastActive()
                loadToolbarAvatar()
            }
            swipeRefresh.isRefreshing = false
        }

        // Load messages
        messages.addAll(chat.messages)
        adapter = MessageAdapter(myId) { selectedMsg ->
            showMessageOptionsDialog(selectedMsg)
        }
        recyclerMessages.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recyclerMessages.adapter = adapter
        adapter.submitList(messages.toList())

        // Resolve thread id and listen for new messages
        val threadId = getThreadId()
        if (threadId != null) {
            listenForNewMessages(threadId)
        }

        // Input mode toggle & Send/Record buttons
        val btnRecord = findViewById<FrameLayout>(R.id.btnRecord)
        val btnCancelRecord = findViewById<TextView>(R.id.btnCancelRecord)
        val btnSendRecord = findViewById<FrameLayout>(R.id.btnSendRecord)

        btnSend.visibility = View.GONE
        btnRecord.visibility = View.VISIBLE

        editMessage.addTextChangedListener { text ->
            val hasText = !text.isNullOrBlank()
            btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
            btnRecord.visibility = if (hasText) View.GONE else View.VISIBLE
        }

        btnRecord.setOnClickListener {
            checkRecordPermissionAndStart()
        }

        btnCancelRecord.setOnClickListener {
            stopVoiceRecording(send = false)
        }

        btnSendRecord.setOnClickListener {
            stopVoiceRecording(send = true)
        }

        btnSend.setOnClickListener {
            val text = editMessage.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isEmpty()) return@setOnClickListener

            val reply = replyingMessage
            val msg = ChatMessage(
                id = UUID.randomUUID().toString(),
                senderId = myId,
                senderName = chat.userName,
                message = text,
                timestamp = System.currentTimeMillis(),
                status = "sent",
                type = "text",
                replyToId = reply?.id,
                replyToSender = reply?.let { if (it.senderId == myId) "You" else (chat.partnerName.ifEmpty { "AnnoUser" }) },
                replyToText = reply?.let { if (it.type == "voice") "🎤 Voice message" else it.message }
            )

            messages.add(msg)
            adapter.submitList(messages.toList())
            recyclerMessages.scrollToPosition(messages.size - 1)
            persistMessages()
            editMessage.text?.clear()
            clearReplyMessage()

            // Write to Firebase /threads/{threadId}/messages
            val tid = getThreadId()
            if (tid != null) {
                UserDatabase.registerUserThread(myId, chat.partnerAccountId, tid)
                val payload = mutableMapOf<String, Any>(
                    "id" to msg.id,
                    "senderId" to msg.senderId,
                    "senderName" to msg.senderName,
                    "message" to msg.message,
                    "timestamp" to msg.timestamp,
                    "status" to "sent",
                    "type" to "text"
                )
                msg.replyToId?.let { payload["replyToId"] = it }
                msg.replyToSender?.let { payload["replyToSender"] = it }
                msg.replyToText?.let { payload["replyToText"] = it }

                FirebaseDatabase.getInstance().reference
                    .child("threads").child(tid).child("messages")
                    .push().setValue(payload)
            }
        }

        btnOverflowMenu.setOnClickListener { view -> showOverflowMenu(view) }
    }

    private fun showMessageOptionsDialog(message: ChatMessage) {
        val options = mutableListOf<String>()
        options.add("Reply")
        val canEdit = (message.senderId == myId) && (message.type == "text") && !message.isDeleted
        if (canEdit) {
            options.add("Edit Message")
        }
        if (!message.isDeleted) {
            options.add("Delete Message")
        }

        AlertDialog.Builder(this)
            .setTitle("Message Options")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Reply" -> setReplyMessage(message)
                    "Edit Message" -> showEditMessageDialog(message)
                    "Delete Message" -> confirmDeleteMessage(message)
                }
            }
            .show()
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
            persistMessages()
        }

        val tid = getThreadId()
        if (tid != null) {
            val ref = FirebaseDatabase.getInstance().reference.child("threads").child(tid).child("messages")
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
            persistMessages()
        }

        val tid = getThreadId()
        if (tid != null) {
            val ref = FirebaseDatabase.getInstance().reference.child("threads").child(tid).child("messages")
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

    private fun setReplyMessage(message: ChatMessage) {
        replyingMessage = message
        val layoutReplyPreview = findViewById<View>(R.id.layoutReplyPreview) ?: return
        val tvReplyingToSender = findViewById<TextView>(R.id.tvReplyingToSender) ?: return
        val tvReplyingToText = findViewById<TextView>(R.id.tvReplyingToText) ?: return

        val senderName = if (message.senderId == myId) "You" else (chat.partnerName.ifEmpty { "AnnoUser" })
        val snippet = if (message.type == "voice") "🎤 Voice message" else message.message

        tvReplyingToSender.text = "Replying to $senderName"
        tvReplyingToText.text = snippet
        layoutReplyPreview.visibility = View.VISIBLE

        val editMessage = findViewById<EditText>(R.id.editMessage)
        editMessage.requestFocus()
    }

    private fun clearReplyMessage() {
        replyingMessage = null
        findViewById<View>(R.id.layoutReplyPreview)?.visibility = View.GONE
    }

    private fun checkRecordPermissionAndStart() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startVoiceRecording()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    private fun startVoiceRecording() {
        try {
            val file = java.io.File(cacheDir, "voice_${System.currentTimeMillis()}.m4a")
            recordedAudioFile = file

            val recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.media.MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                android.media.MediaRecorder()
            }

            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            recorder.start()

            mediaRecorder = recorder
            isRecording = true
            recordStartTime = System.currentTimeMillis()

            findViewById<View>(R.id.layoutTextInput).visibility = View.GONE
            findViewById<View>(R.id.layoutRecording).visibility = View.VISIBLE

            startRecordTimer()
        } catch (_: Exception) {
            Toast.makeText(this, "Failed to start recording", Toast.LENGTH_SHORT).show()
            stopVoiceRecording(send = false)
        }
    }

    private fun stopVoiceRecording(send: Boolean) {
        stopRecordTimer()
        if (!isRecording) return

        val file = recordedAudioFile
        val durationMs = System.currentTimeMillis() - recordStartTime
        isRecording = false

        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        findViewById<View>(R.id.layoutRecording).visibility = View.GONE
        findViewById<View>(R.id.layoutTextInput).visibility = View.VISIBLE

        if (send && file != null && file.exists() && file.length() > 0L) {
            if (durationMs < 1000) {
                Toast.makeText(this, "Voice message too short", Toast.LENGTH_SHORT).show()
                file.delete()
                return
            }

            try {
                val bytes = file.readBytes()
                val audioBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
                sendVoiceMessage(audioBase64, durationMs)
            } catch (_: Exception) {
                Toast.makeText(this, "Failed to send voice message", Toast.LENGTH_SHORT).show()
            }
        } else {
            file?.delete()
        }
    }

    private fun sendVoiceMessage(audioBase64: String, durationMs: Long) {
        val reply = replyingMessage
        val msg = ChatMessage(
            id = UUID.randomUUID().toString(),
            senderId = myId,
            senderName = chat.userName,
            message = "🎤 Voice message",
            timestamp = System.currentTimeMillis(),
            status = "sent",
            type = "voice",
            audioData = audioBase64,
            durationMs = durationMs,
            replyToId = reply?.id,
            replyToSender = reply?.let { if (it.senderId == myId) "You" else (chat.partnerName.ifEmpty { "AnnoUser" }) },
            replyToText = reply?.let { if (it.type == "voice") "🎤 Voice message" else it.message }
        )

        messages.add(msg)
        adapter.submitList(messages.toList())
        recyclerMessages.scrollToPosition(messages.size - 1)
        persistMessages()
        clearReplyMessage()

        val tid = getThreadId()
        if (tid != null) {
            UserDatabase.registerUserThread(myId, chat.partnerAccountId, tid)
            val payload = mutableMapOf<String, Any>(
                "id" to msg.id,
                "senderId" to msg.senderId,
                "senderName" to msg.senderName,
                "message" to msg.message,
                "timestamp" to msg.timestamp,
                "status" to "sent",
                "type" to "voice",
                "audioData" to audioBase64,
                "durationMs" to durationMs
            )
            msg.replyToId?.let { payload["replyToId"] = it }
            msg.replyToSender?.let { payload["replyToSender"] = it }
            msg.replyToText?.let { payload["replyToText"] = it }

            FirebaseDatabase.getInstance().reference
                .child("threads").child(tid).child("messages")
                .push().setValue(payload)
        }
    }

    private fun startRecordTimer() {
        stopRecordTimer()
        val tvRecordTimer = findViewById<TextView>(R.id.tvRecordTimer)
        val runnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    val sec = ((System.currentTimeMillis() - recordStartTime) / 1000).toInt()
                    val m = sec / 60
                    val s = sec % 60
                    tvRecordTimer.text = String.format(java.util.Locale.getDefault(), "Recording %d:%02d", m, s)
                    recordTimerHandler.postDelayed(this, 500)
                }
            }
        }
        recordTimerRunnable = runnable
        recordTimerHandler.post(runnable)
    }

    private fun stopRecordTimer() {
        recordTimerRunnable?.let { recordTimerHandler.removeCallbacks(it) }
        recordTimerRunnable = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoiceRecording()
            } else {
                Toast.makeText(this, "Microphone permission required to record voice notes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Derive a stable thread id from both user ids (sorted). Same on both devices. */
    private fun getThreadId(): String? {
        // If we already have one stored, use it
        if (!chat.threadId.isNullOrBlank()) return chat.threadId

        // Derive from the two distinct sender IDs in the messages
        val senderIds = chat.messages
            .map { it.senderId }
            .filter { it.isNotBlank() && it != "system" }
            .distinct()

        val threadId = if (senderIds.size == 2) {
            senderIds.sorted().joinToString("_")
        } else if (!myId.isNullOrBlank() && !chat.partnerAccountId.isNullOrBlank()) {
            listOf(myId, chat.partnerAccountId!!).sorted().joinToString("_")
        } else {
            return null
        }

        UserDatabase.registerUserThread(myId, chat.partnerAccountId, threadId)

        // Backfill threadId into local storage
        chat = chat.copy(threadId = threadId)
        val allChats = ChatStorage.getSavedChats(this).toMutableList()
        val idx = allChats.indexOfFirst { it.id == chatId }
        if (idx >= 0) {
            allChats[idx] = chat
            ChatStorage.persistChats(this, allChats)
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
                val text = snapshot.child("message").getValue(String::class.java) ?: ""
                val ts = snapshot.child("timestamp").getValue(Long::class.java) ?: return
                val type = snapshot.child("type").getValue(String::class.java) ?: "text"
                val audioData = snapshot.child("audioData").getValue(String::class.java)
                val durationMs = snapshot.child("durationMs").getValue(Long::class.java) ?: 0L

                val replyToId = snapshot.child("replyToId").getValue(String::class.java)
                val replyToSender = snapshot.child("replyToSender").getValue(String::class.java)
                val replyToText = snapshot.child("replyToText").getValue(String::class.java)
                val isEdited = snapshot.child("isEdited").getValue(Boolean::class.java) ?: false
                val isDeleted = snapshot.child("isDeleted").getValue(Boolean::class.java) ?: false

                val msg = ChatMessage(
                    id = msgId,
                    senderId = senderId,
                    senderName = senderName,
                    message = text,
                    timestamp = ts,
                    status = if (senderId == myId) "sent" else "read",
                    type = type,
                    audioData = audioData,
                    durationMs = durationMs,
                    replyToId = replyToId,
                    replyToSender = replyToSender,
                    replyToText = replyToText,
                    isEdited = isEdited,
                    isDeleted = isDeleted
                )

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
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx >= 0) {
                    val status = s.child("status").getValue(String::class.java) ?: messages[idx].status
                    val text = s.child("message").getValue(String::class.java) ?: messages[idx].message
                    val isEdited = s.child("isEdited").getValue(Boolean::class.java) ?: messages[idx].isEdited
                    val isDeleted = s.child("isDeleted").getValue(Boolean::class.java) ?: messages[idx].isDeleted

                    messages[idx] = messages[idx].copy(
                        status = status,
                        message = text,
                        isEdited = isEdited,
                        isDeleted = isDeleted
                    )
                    adapter.submitList(messages.toList())
                    persistMessages()
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
            ?: chat.messages.firstOrNull { it.senderId != myId && it.senderId != "system" }?.senderId ?: return
        FirebaseDatabase.getInstance().reference
            .child("users").child(partnerUid).child("lastActive")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(s: DataSnapshot) {
                    tvToolbarSubtitle.text = formatLastActive(s.getValue(Long::class.java))
                }
                override fun onCancelled(e: DatabaseError) {}
            })
    }

    private fun loadToolbarAvatar() {
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId && it.senderId != "system" }?.senderId ?: return
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

    private fun showPartnerProfileDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_partner_profile, null)
        val ivAvatar = dialogView.findViewById<CircleImageView>(R.id.ivPartnerAvatar)
        val tvName = dialogView.findViewById<TextView>(R.id.tvPartnerName)
        val tvGender = dialogView.findViewById<TextView>(R.id.tvPartnerGender)
        val tvAge = dialogView.findViewById<TextView>(R.id.tvPartnerAge)
        val tvCity = dialogView.findViewById<TextView>(R.id.tvPartnerCity)

        tvName.text = chat.partnerName.ifEmpty { "AnnoUser" }
        tvGender.text = chat.partnerGender ?: "Not specified"
        tvAge.text = if (chat.partnerAge != null && chat.partnerAge!! >= 0) "${chat.partnerAge} yrs" else "Not specified"
        tvCity.text = chat.partnerCity ?: "Not specified"

        ivAvatar.setImageResource(R.drawable.ic_default_avatar)
        if (!chat.partnerAvatar.isNullOrEmpty()) {
            try {
                val bytes = android.util.Base64.decode(chat.partnerAvatar, android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
            } catch (_: Exception) {}
        }

        when (chat.partnerGender) {
            "Female" -> ivAvatar.borderColor = android.graphics.Color.parseColor("#E91E63")
            else -> ivAvatar.borderColor = resources.getColor(R.color.primary, theme)
        }

        // Derive partner uid — from stored accountId or from messages
        val partnerUid = chat.partnerAccountId
            ?: chat.messages.firstOrNull { it.senderId != myId && it.senderId != "system" }?.senderId

        var fetchedAvatar: String? = chat.partnerAvatar

        if (partnerUid != null) {
            // 1. Check local cache if fields are "Not specified"
            val cachedGender = TestSession.cachedProfileGender(this, partnerUid)
            val cachedAge = TestSession.cachedProfileAge(this, partnerUid)
            val cachedCity = TestSession.cachedProfileCity(this, partnerUid)
            val cachedName = TestSession.cachedProfileDisplayName(this, partnerUid)
            val cachedAvatar = TestSession.cachedProfileAvatar(this, partnerUid)
                ?: getSharedPreferences("anonchat_prefs", MODE_PRIVATE).getString("avatar_$partnerUid", null)

            if (!cachedName.isNullOrEmpty() && tvName.text == "AnnoUser") tvName.text = cachedName
            if (!cachedGender.isNullOrEmpty() && tvGender.text == "Not specified") tvGender.text = cachedGender
            if (cachedAge != null && tvAge.text == "Not specified") tvAge.text = "$cachedAge yrs"
            if (!cachedCity.isNullOrEmpty() && tvCity.text == "Not specified") tvCity.text = cachedCity
            if (!cachedAvatar.isNullOrEmpty() && fetchedAvatar.isNullOrEmpty()) {
                fetchedAvatar = cachedAvatar
                try {
                    val bytes = android.util.Base64.decode(cachedAvatar, android.util.Base64.DEFAULT)
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
                } catch (_: Exception) {}
            }

            // 2. Fetch live profile data from Firebase Realtime Database
            FirebaseDatabase.getInstance().reference
                .child("users").child(partnerUid)
                .addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(s: DataSnapshot) {
                        if (!s.exists()) return
                        val prof = s.child("profile")

                        val name = prof.child("displayName").getValue(String::class.java)
                            ?: s.child("displayName").getValue(String::class.java)
                            ?: s.child("userName").getValue(String::class.java)

                        val gender = prof.child("gender").getValue(String::class.java)
                            ?: s.child("gender").getValue(String::class.java)

                        val rawAge = prof.child("age").value ?: s.child("age").value
                        val age = rawAge?.toString()?.toIntOrNull()

                        val city = prof.child("city").getValue(String::class.java)
                            ?: s.child("city").getValue(String::class.java)

                        val avatarData = s.child("avatar").getValue(String::class.java)
                            ?: prof.child("avatar").getValue(String::class.java)

                        if (!name.isNullOrEmpty()) tvName.text = name
                        if (!gender.isNullOrEmpty()) tvGender.text = gender
                        if (age != null && age >= 0) tvAge.text = "$age yrs"
                        if (!city.isNullOrEmpty()) tvCity.text = city

                        if (!avatarData.isNullOrEmpty()) {
                            fetchedAvatar = avatarData
                            try {
                                val bytes = android.util.Base64.decode(avatarData, android.util.Base64.DEFAULT)
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) ivAvatar.setImageBitmap(bitmap)
                            } catch (_: Exception) {}
                        }

                        // Persist fetched details back to ChatStorage so it loads instantly next time
                        val updatedChat = chat.copy(
                            partnerName = name ?: chat.partnerName,
                            partnerGender = gender ?: chat.partnerGender,
                            partnerAge = age ?: chat.partnerAge,
                            partnerCity = city ?: chat.partnerCity,
                            partnerAvatar = avatarData ?: chat.partnerAvatar,
                            partnerAccountId = partnerUid
                        )
                        if (updatedChat != chat) {
                            chat = updatedChat
                            ChatStorage.updateSavedChat(this@SavedChatActivity, updatedChat)
                        }
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

    override fun onResume() {
        super.onResume()
        if (::chat.isInitialized) {
            MessageNotificationService.activeChatId = chat.id
        }
    }

    override fun onPause() {
        super.onPause()
        if (isRecording) {
            stopVoiceRecording(send = false)
        }
        if (::chat.isInitialized && MessageNotificationService.activeChatId == chat.id) {
            MessageNotificationService.activeChatId = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            stopVoiceRecording(send = false)
        }
        if (::chat.isInitialized && MessageNotificationService.activeChatId == chat.id) {
            MessageNotificationService.activeChatId = null
        }
        threadListener?.let { threadRef?.removeEventListener(it) }
    }
}
