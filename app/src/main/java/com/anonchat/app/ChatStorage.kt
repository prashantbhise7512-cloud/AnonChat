package com.anonchat.app

import android.content.Context
import com.anonchat.app.model.ChatMessage
import com.anonchat.app.model.SavedChat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Simple SharedPreferences-based storage for saved chats.
 */
object ChatStorage {

    private const val PREFS_NAME = "anonchat_prefs"
    private const val KEY_SAVED_CHATS = "saved_chats"
    private const val MAX_SAVED = 20

    fun getSavedChats(context: Context): List<SavedChat> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SAVED_CHATS, "[]") ?: "[]"
        return parseSavedChats(json)
    }

    fun saveChat(context: Context, chat: SavedChat) {
        val chats = getSavedChats(context).toMutableList()
        chats.add(0, chat)
        if (chats.size > MAX_SAVED) chats.removeLast()
        persistChats(context, chats)
    }

    fun deleteChat(context: Context, chatId: String) {
        val chats = getSavedChats(context).filter { it.id != chatId }
        persistChats(context, chats)
    }

    fun persistChats(context: Context, chats: List<SavedChat>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        chats.forEach { chat ->
            val chatObj = JSONObject().apply {
                put("id", chat.id)
                put("savedAt", chat.savedAt)
                put("userName", chat.userName)
                val msgsArray = JSONArray()
                chat.messages.forEach { msg ->
                    msgsArray.put(JSONObject().apply {
                        put("id", msg.id)
                        put("senderId", msg.senderId)
                        put("senderName", msg.senderName)
                        put("message", msg.message)
                        put("timestamp", msg.timestamp)
                    })
                }
                put("messages", msgsArray)
            }
            jsonArray.put(chatObj)
        }
        prefs.edit().putString(KEY_SAVED_CHATS, jsonArray.toString()).apply()
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
                        timestamp = m.getLong("timestamp")
                    ))
                }
                result.add(SavedChat(
                    id = obj.getString("id"),
                    savedAt = obj.getLong("savedAt"),
                    userName = obj.getString("userName"),
                    messages = messages
                ))
            }
        } catch (_: Exception) {}
        return result
    }
}
