package com.anonchat.app

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject

/**
 * UserDatabase helper: `/users` is the MASTER TABLE storing all user records
 * keyed by unique User ID (uid) and linked to a unique phone number.
 */
object UserDatabase {

    fun sanitizePhoneNumber(phone: String): String {
        return phone.replace(Regex("[^0-9+]"), "").replace("+", "p")
    }

    /**
     * Saves or updates a master user record in the `/users` MASTER TABLE.
     *
     * Master Table Schema (`/users/$uid`):
     *   - uid: String
     *   - phoneNumber: String (unique)
     *   - sanitizedPhone: String
     *   - displayName: String
     *   - gender: String?
     *   - age: Int?
     *   - city: String?
     *   - avatar: String? (Base64)
     *   - createdAt: Long
     *   - updatedAt: Long
     *   - lastActive: Long
     *   - isProfileComplete: Boolean
     *   - profile: Map { displayName, gender, age, city, phoneNumber, updatedAt }
     *
     * Secondary Phone Index (`/phone_to_uid/$sanitizedPhone`):
     *   - String pointing directly to $uid in the /users Master Table
     */
    fun saveUser(
        context: Context,
        uid: String,
        phoneNumber: String,
        displayName: String,
        gender: String? = null,
        age: Int? = null,
        city: String? = null,
        avatarBase64: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        val sanitizedPhone = sanitizePhoneNumber(phoneNumber)
        val now = System.currentTimeMillis()

        // 1. Save master record locally in SharedPreferences
        val prefs = context.getSharedPreferences("anonchat_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (phoneNumber.isNotEmpty()) {
                putString("current_phone_number", phoneNumber)
                putString("phone_$uid", phoneNumber)
                if (sanitizedPhone.isNotEmpty()) {
                    putString("uid_by_phone_$sanitizedPhone", uid)
                }
            }
            val json = JSONObject().apply {
                put("uid", uid)
                put("phoneNumber", phoneNumber)
                put("displayName", displayName)
                if (gender != null) put("gender", gender) else put("gender", JSONObject.NULL)
                if (age != null) put("age", age) else put("age", JSONObject.NULL)
                if (city != null) put("city", city) else put("city", JSONObject.NULL)
                put("updatedAt", now)
            }.toString()
            putString("profile_$uid", json)
            if (!avatarBase64.isNullOrEmpty()) {
                putString("avatar_$uid", avatarBase64)
            }
            apply()
        }

        // Keep TestSession cache synced so profile screen displays saved details immediately
        TestSession.cacheDisplayName(context, uid, displayName)
        TestSession.cacheProfile(context, uid, displayName, gender, age, city, avatarBase64)

        // 2. Save in Firebase Realtime Database MASTER TABLE `/users/$uid`
        val db = FirebaseDatabase.getInstance().reference

        val profileMap = mutableMapOf<String, Any>(
            "displayName" to displayName,
            "phoneNumber" to phoneNumber,
            "updatedAt" to now
        )
        if (gender != null) profileMap["gender"] = gender
        if (age != null) profileMap["age"] = age
        if (city != null) profileMap["city"] = city

        val masterUserRecord = mutableMapOf<String, Any>(
            "uid" to uid,
            "phoneNumber" to phoneNumber,
            "sanitizedPhone" to sanitizedPhone,
            "displayName" to displayName,
            "updatedAt" to now,
            "lastActive" to now,
            "isProfileComplete" to (displayName.isNotEmpty() && displayName != "AnnoUser"),
            "profile" to profileMap
        )
        if (gender != null) masterUserRecord["gender"] = gender
        if (age != null) masterUserRecord["age"] = age
        if (city != null) masterUserRecord["city"] = city

        val updates = mutableMapOf<String, Any>(
            "/users/$uid" to masterUserRecord
        )

        // Point phone index to master record in /users/$uid
        if (sanitizedPhone.isNotEmpty()) {
            updates["/phone_to_uid/$sanitizedPhone"] = uid
            updates["/users_by_phone/$sanitizedPhone"] = masterUserRecord
        }

        if (!avatarBase64.isNullOrEmpty()) {
            updates["/users/$uid/avatar"] = avatarBase64
        }

        db.updateChildren(updates).addOnCompleteListener { task ->
            onComplete?.invoke(task.isSuccessful)
        }
    }

    /**
     * Looks up user master record from `/users` Master Table using the unique phone number.
     */
    fun findUserByPhone(
        context: Context,
        phoneNumber: String,
        onResult: (uid: String?, masterUserRecord: Map<String, Any>?) -> Unit
    ) {
        val sanitizedPhone = sanitizePhoneNumber(phoneNumber)
        val prefs = context.getSharedPreferences("anonchat_prefs", Context.MODE_PRIVATE)

        // Check local master record cache first
        val cachedUid = if (sanitizedPhone.isNotEmpty()) prefs.getString("uid_by_phone_$sanitizedPhone", null) else null
        if (!cachedUid.isNullOrEmpty()) {
            val profileJson = prefs.getString("profile_$cachedUid", null)
            if (!profileJson.isNullOrEmpty()) {
                try {
                    val jsonObj = JSONObject(profileJson)
                    val map = mutableMapOf<String, Any>(
                        "uid" to cachedUid,
                        "phoneNumber" to phoneNumber,
                        "displayName" to jsonObj.optString("displayName", "AnnoUser")
                    )
                    if (!jsonObj.isNull("gender")) map["gender"] = jsonObj.getString("gender")
                    if (!jsonObj.isNull("age")) map["age"] = jsonObj.getInt("age")
                    if (!jsonObj.isNull("city")) map["city"] = jsonObj.getString("city")
                    onResult(cachedUid, map)
                    return
                } catch (_: Exception) {}
            }
        }

        if (sanitizedPhone.isEmpty()) {
            onResult(null, null)
            return
        }

        val db = FirebaseDatabase.getInstance().reference

        // 1. Look up UID from phone index
        db.child("phone_to_uid").child(sanitizedPhone).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val foundUid = snapshot.getValue(String::class.java)
                if (foundUid != null) {
                    // Fetch full master user record from `/users/$foundUid` master table
                    db.child("users").child(foundUid).addListenerForSingleValueEvent(object : ValueEventListener {
                        override fun onDataChange(userSnap: DataSnapshot) {
                            @Suppress("UNCHECKED_CAST")
                            val userRecord = userSnap.value as? Map<String, Any>
                            onResult(foundUid, userRecord)
                        }

                        override fun onCancelled(error: DatabaseError) {
                            onResult(foundUid, null)
                        }
                    })
                } else {
                    // Fallback: direct query on /users master table by phoneNumber
                    db.child("users").orderByChild("phoneNumber").equalTo(phoneNumber)
                        .addListenerForSingleValueEvent(object : ValueEventListener {
                            override fun onDataChange(querySnap: DataSnapshot) {
                                if (querySnap.exists() && querySnap.children.iterator().hasNext()) {
                                    val child = querySnap.children.iterator().next()
                                    val masterUid = child.key ?: child.child("uid").getValue(String::class.java)
                                    @Suppress("UNCHECKED_CAST")
                                    val recordMap = child.value as? Map<String, Any>
                                    onResult(masterUid, recordMap)
                                } else {
                                    onResult(null, null)
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                onResult(null, null)
                            }
                        })
                }
            }

            override fun onCancelled(error: DatabaseError) {
                onResult(null, null)
            }
        })
    }

    /**
     * Registers a thread ID under /user_threads/$userId so that both participants
     * continue receiving notifications and messages even if local chat is deleted.
     */
    fun registerUserThread(userId: String?, partnerId: String?, threadId: String?) {
        if (userId.isNullOrBlank() || threadId.isNullOrBlank()) return

        val db = FirebaseDatabase.getInstance().reference
        val updates = mutableMapOf<String, Any>(
            "/user_threads/$userId/$threadId" to true,
            "/threads/$threadId/users/$userId" to true
        )
        if (!partnerId.isNullOrBlank()) {
            updates["/user_threads/$partnerId/$threadId"] = true
            updates["/threads/$threadId/users/$partnerId"] = true
        }
        db.updateChildren(updates)
    }
}
