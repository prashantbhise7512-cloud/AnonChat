package com.anonchat.app

import android.content.Context
import com.anonchat.app.model.ChatMessage
import com.anonchat.app.model.SavedChat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage for saved chats maintained per user's logged-in phone number.
 */
object ChatStorage {

    private const val PREFS_NAME = "anonchat_prefs"
    private const val KEY_DEFAULT_SAVED_CHATS = "saved_chats"
    private const val MAX_SAVED = 30

    fun getActivePhone(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("current_phone_number", null)
            ?.takeIf { it.isNotBlank() }
    }

    private fun getStorageKey(context: Context): String {
        val phone = getActivePhone(context)
        return if (!phone.isNullOrBlank()) {
            val sanitized = UserDatabase.sanitizePhoneNumber(phone)
            "saved_chats_$sanitized"
        } else {
            KEY_DEFAULT_SAVED_CHATS
        }
    }

    fun getSavedChats(context: Context): List<SavedChat> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = getStorageKey(context)
        var json = prefs.getString(key, null)

        // Legacy fallback/migration if key-specific storage is empty
        if (json.isNullOrEmpty() && key != KEY_DEFAULT_SAVED_CHATS) {
            val legacyJson = prefs.getString(KEY_DEFAULT_SAVED_CHATS, null)
            if (!legacyJson.isNullOrEmpty()) {
                json = legacyJson
                prefs.edit().putString(key, legacyJson).apply()
            }
        }

        return parseSavedChats(json ?: "[]")
    }

    fun saveChat(context: Context, chat: SavedChat) {
        val chats = getSavedChats(context).toMutableList()
        val index = chats.indexOfFirst { it.id == chat.id }
        if (index >= 0) {
            chats[index] = chat
        } else {
            chats.add(0, chat)
        }
        if (chats.size > MAX_SAVED) chats.removeLast()
        persistChats(context, chats, syncCloud = true)
    }

    fun deleteChat(context: Context, chatId: String) {
        val chats = getSavedChats(context).filter { it.id != chatId }
        persistChats(context, chats, syncCloud = true)
    }

    fun persistChats(context: Context, chats: List<SavedChat>, syncCloud: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = serializeChats(chats)
        val key = getStorageKey(context)
        prefs.edit().putString(key, jsonArray.toString()).apply()

        if (syncCloud) {
            syncToCloud(context, chats)
        }
    }

    fun syncToCloud(context: Context, chats: List<SavedChat>? = null) {
        val phone = getActivePhone(context) ?: return
        val sanitizedPhone = UserDatabase.sanitizePhoneNumber(phone)
        val listToSync = chats ?: getSavedChats(context)
        val jsonString = serializeChats(listToSync).toString()

        val db = FirebaseDatabase.getInstance().reference
        val uid = TestSession.currentUserId(context) ?: TestSession.uid(context)

        val updates = mutableMapOf<String, Any>(
            "/user_saved_chats/$sanitizedPhone" to jsonString
        )
        if (!uid.isNullOrBlank()) {
            updates["/users/$uid/saved_chats"] = jsonString
        }

        db.updateChildren(updates)
    }

    fun syncFromCloud(context: Context, onComplete: ((List<SavedChat>) -> Unit)? = null) {
        val phone = getActivePhone(context)
        val localChats = getSavedChats(context)

        if (phone.isNullOrBlank()) {
            onComplete?.invoke(localChats)
            return
        }

        val sanitizedPhone = UserDatabase.sanitizePhoneNumber(phone)
        val uid = TestSession.currentUserId(context) ?: TestSession.uid(context)

        val db = FirebaseDatabase.getInstance().reference
        val primaryRef = if (!uid.isNullOrBlank()) {
            db.child("users").child(uid).child("saved_chats")
        } else {
            db.child("user_saved_chats").child(sanitizedPhone)
        }

        primaryRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val cloudJson = snapshot.getValue(String::class.java)
                if (!cloudJson.isNullOrEmpty()) {
                    val cloudChats = parseSavedChats(cloudJson)
                    val merged = mergeChats(localChats, cloudChats)
                    persistChats(context, merged, syncCloud = false)
                    onComplete?.invoke(merged)
                } else {
                    // Fallback to /user_saved_chats/$sanitizedPhone
                    db.child("user_saved_chats").child(sanitizedPhone)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(fallbackSnap: DataSnapshot) {
                                val fbJson = fallbackSnap.getValue(String::class.java)
                                if (!fbJson.isNullOrEmpty()) {
                                    val cloudChats = parseSavedChats(fbJson)
                                    val merged = mergeChats(localChats, cloudChats)
                                    persistChats(context, merged, syncCloud = false)
                                    onComplete?.invoke(merged)
                                } else {
                                    onComplete?.invoke(localChats)
                                }
                            }
                            override fun onCancelled(error: DatabaseError) {
                                onComplete?.invoke(localChats)
                            }
                        })
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onComplete?.invoke(localChats)
            }
        })
    }

    private fun mergeChats(local: List<SavedChat>, cloud: List<SavedChat>): List<SavedChat> {
        val map = mutableMapOf<String, SavedChat>()
        (local + cloud).forEach { chat ->
            val existing = map[chat.id]
            if (existing == null) {
                map[chat.id] = chat
            } else {
                val combinedMessages = (existing.messages + chat.messages)
                    .groupBy { it.id }
                    .map { (_, msgs) -> msgs.first() }
                    .sortedBy { it.timestamp }
                map[chat.id] = if (chat.savedAt >= existing.savedAt) {
                    chat.copy(messages = combinedMessages)
                } else {
                    existing.copy(messages = combinedMessages)
                }
            }
        }
        return map.values.sortedByDescending { it.savedAt }
    }

    fun updateChatMessages(context: Context, chatId: String, newMessages: List<ChatMessage>) {
        val chats = getSavedChats(context).toMutableList()
        val index = chats.indexOfFirst { it.id == chatId }
        if (index >= 0) {
            chats[index] = chats[index].copy(messages = newMessages)
            persistChats(context, chats, syncCloud = true)
        }
    }

    fun updateSavedChat(context: Context, updatedChat: SavedChat) {
        val chats = getSavedChats(context).toMutableList()
        val index = chats.indexOfFirst { it.id == updatedChat.id }
        if (index >= 0) {
            chats[index] = updatedChat
            persistChats(context, chats, syncCloud = true)
        }
    }

    fun appendMessageToChat(context: Context, chatId: String, message: ChatMessage) {
        val chats = getSavedChats(context).toMutableList()
        val index = chats.indexOfFirst { it.id == chatId }
        if (index >= 0) {
            val chat = chats[index]
            if (chat.messages.any { it.id == message.id }) return
            val updated = chat.copy(messages = chat.messages + message)
            chats[index] = updated
            persistChats(context, chats, syncCloud = true)
        }
    }

    fun backfillThreadIds(context: Context) {
        val currentUid = TestSession.currentUserId(context) ?: TestSession.uid(context)
        val chats = getSavedChats(context)
        val updated = chats.map { chat ->
            val partnerUid = chat.partnerAccountId ?: chat.messages.firstOrNull { it.senderId != currentUid }?.senderId
            val threadId = chat.threadId ?: deriveThreadId(chat, currentUid, partnerUid)
            if (chat.partnerAccountId != partnerUid || chat.threadId != threadId) {
                chat.copy(partnerAccountId = partnerUid, threadId = threadId)
            } else {
                chat
            }
        }
        if (updated != chats) {
            persistChats(context, updated, syncCloud = true)
        }
    }

    private fun deriveThreadId(chat: SavedChat, currentUid: String?, partnerUid: String?): String? {
        chat.threadId?.takeIf { it.isNotBlank() }?.let { return it }
        val userIds = listOfNotNull(currentUid, partnerUid).distinct()
        return if (userIds.size == 2) userIds.sorted().joinToString("_") else null
    }

    private fun serializeChats(chats: List<SavedChat>): JSONArray {
        val jsonArray = JSONArray()
        chats.forEach { chat ->
            val chatObj = JSONObject().apply {
                put("id", chat.id)
                put("savedAt", chat.savedAt)
                put("userName", chat.userName)
                put("partnerName", chat.partnerName)
                put("partnerAccountId", chat.partnerAccountId ?: "")
                put("threadId", chat.threadId ?: "")
                put("partnerGender", chat.partnerGender ?: "")
                put("partnerAge", chat.partnerAge ?: -1)
                put("partnerCity", chat.partnerCity ?: "")
                put("partnerAvatar", chat.partnerAvatar ?: "")
                val msgsArray = JSONArray()
                chat.messages.forEach { msg ->
                    msgsArray.put(JSONObject().apply {
                        put("id", msg.id)
                        put("senderId", msg.senderId)
                        put("senderName", msg.senderName)
                        put("message", msg.message)
                        put("timestamp", msg.timestamp)
                        put("status", msg.status)
                    })
                }
                put("messages", msgsArray)
            }
            jsonArray.put(chatObj)
        }
        return jsonArray
    }

    private fun parseSavedChats(json: String): List<SavedChat> {
        val result = mutableListOf<SavedChat>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val msgsArr = obj.getJSONArray("messages")
                val messages = mutableListOf<ChatMessage>()
                for (j in 0 until msgsArr.length()) {
                    val m = msgsArr.getJSONObject(j)
                    messages.add(ChatMessage(
                        id = m.getString("id"),
                        senderId = m.getString("senderId"),
                        senderName = m.getString("senderName"),
                        message = m.getString("message"),
                        timestamp = m.getLong("timestamp"),
                        status = m.optString("status", "sent")
                    ))
                }
                result.add(SavedChat(
                    id = obj.getString("id"),
                    savedAt = obj.getLong("savedAt"),
                    userName = obj.getString("userName"),
                    partnerName = obj.optString("partnerName", ""),
                    partnerAccountId = obj.optString("partnerAccountId", "").ifEmpty { null },
                    threadId = obj.optString("threadId", "").ifEmpty { null },
                    partnerGender = obj.optString("partnerGender", "").ifEmpty { null },
                    partnerAge = obj.optInt("partnerAge", -1).takeIf { it >= 0 },
                    partnerCity = obj.optString("partnerCity", "").ifEmpty { null },
                    partnerAvatar = obj.optString("partnerAvatar", "").ifEmpty { null },
                    messages = messages
                ))
            }
        } catch (_: Exception) {}
        return result
    }
}
