package com.anonchat.app.model

data class ChatMessage(
    val id: String = "",
    val senderId: String = "",
    val senderName: String = "",
    val message: String = "",
    val timestamp: Long = 0L,
    val status: String = "sent" // "sent", "delivered", "read"
)
