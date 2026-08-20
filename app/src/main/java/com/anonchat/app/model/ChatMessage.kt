package com.anonchat.app.model

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val message: String = "",
    val timestamp: Long = 0L,
    val status: String = "sent", // "sent", "delivered", "read"
    val type: String = "text", // "text", "voice"
    val audioData: String? = null, // Base64 encoded audio
    val durationMs: Long = 0L, // Audio duration in milliseconds
    val replyToId: String? = null,
    val replyToSender: String? = null,
    val replyToText: String? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false
)
