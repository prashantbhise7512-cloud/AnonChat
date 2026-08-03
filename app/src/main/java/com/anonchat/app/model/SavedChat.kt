package com.anonchat.app.model

data class SavedChat(
    val id: String = "",
    val savedAt: Long = 0L,
    val userName: String = "",
    val partnerName: String = "",
    val partnerAccountId: String? = null,
    val partnerGender: String? = null,
    val partnerAge: Int? = null,
    val partnerCity: String? = null,
    val partnerAvatar: String? = null,
    val messages: List<ChatMessage> = emptyList()
)
