package com.anonchat.app

object FcmNotifications {
    fun sendMessageNotification(
        context: android.content.Context,
        recipientToken: String?,
        recipientId: String,
        threadId: String,
        chatId: String,
        messageId: String,
        senderName: String,
        body: String
    ) {
        // Push notifications are skipped in this testing mode.
    }
}
