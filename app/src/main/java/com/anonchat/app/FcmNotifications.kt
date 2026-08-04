package com.anonchat.app

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

object FcmNotifications {
    private const val TAG = "FcmNotifications"

    fun buildNotificationData(
        recipientId: String,
        threadId: String,
        chatId: String,
        messageId: String,
        senderName: String,
        body: String,
        timestamp: Long = System.currentTimeMillis()
    ): Map<String, String> = mapOf(
        "type" to "chat_message",
        "recipientId" to recipientId,
        "threadId" to threadId,
        "chatId" to chatId,
        "messageId" to messageId,
        "senderName" to senderName,
        "body" to body,
        "timestamp" to timestamp.toString()
    )

    fun sendMessageNotification(
        context: Context,
        recipientToken: String?,
        recipientId: String,
        threadId: String,
        chatId: String,
        messageId: String,
        senderName: String,
        body: String
    ) {
        Log.i(TAG, "Notification delivery is handled by the Cloud Function; no direct app-side FCM send is used.")
    }

    fun subscribeToTopic(context: Context, topic: String) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic)
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w(TAG, "Subscribe failed for topic $topic")
                }
            }
    }

    fun registerToken(context: Context, token: String) {
        val currentUid = TestSession.currentUserId(context)
        if (currentUid.isNullOrBlank()) return
        FirebaseDatabase.getInstance().reference
            .child("users")
            .child(currentUid)
            .child("fcmToken")
            .setValue(token)
    }
}
