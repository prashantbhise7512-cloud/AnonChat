package com.anonchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmNotificationsTest {

    @Test
    fun buildNotificationData_containsExpectedFields() {
        val data = FcmNotifications.buildNotificationData(
            recipientId = "recipient",
            threadId = "thread-1",
            chatId = "chat-1",
            messageId = "message-1",
            senderName = "Alice",
            body = "hi"
        )

        assertEquals("chat_message", data["type"])
        assertEquals("recipient", data["recipientId"])
        assertEquals("thread-1", data["threadId"])
        assertEquals("chat-1", data["chatId"])
        assertEquals("message-1", data["messageId"])
        assertEquals("Alice", data["senderName"])
        assertEquals("hi", data["body"])
        assertTrue((data["timestamp"] ?: "0").toLong() > 0L)
    }
}
