package com.anonchat.app

import android.content.Context
import com.google.firebase.auth.FirebaseAuth

/**
 * Local stand-in for an auth session while [AuthActivity.TEST_MODE] is true.
 *
 * The key names and [deriveAccountId] below MUST stay identical to the TestSession object in
 * `web/app.js` so the same phone number produces the same account on both clients.
 *
 * A Firebase anonymous sign-in is attempted on top of this so Realtime Database rules
 * (which require `auth != null`) still pass. If anonymous auth is unavailable, the app keeps
 * working against this local session and falls back to locally cached profile data.
 *
 * Remove this file (and the TEST_MODE branches that use it) before shipping.
 */
object TestSession {

    private const val PREFS = "anonchat_prefs"
    private const val KEY_ACTIVE = "anonchat_test_active"
    private const val KEY_UID = "anonchat_test_uid"
    private const val KEY_PHONE = "anonchat_test_phone"
    private const val KEY_NORMALIZED_PHONE = "anonchat_normalized_phone"
    private const val KEY_PROFILE_ID = "anonchat_profile_id"
    private const val PROFILE_PREFIX = "anonchat_test_profile_"
    private const val PROFILE_DISPLAY_NAME_PREFIX = "anonchat_profile_display_name_"
    private const val PROFILE_GENDER_PREFIX = "anonchat_profile_gender_"
    private const val PROFILE_AGE_PREFIX = "anonchat_profile_age_"
    private const val PROFILE_CITY_PREFIX = "anonchat_profile_city_"
    private const val PROFILE_AVATAR_PREFIX = "anonchat_profile_avatar_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Shared derivation: "test_" plus the normalized 10-digit number. */
    fun deriveAccountId(phoneNumber: String?): String {
        val digits = normalizePhoneNumber(phoneNumber)
        return if (digits.isEmpty()) "test_anonymous" else "test_$digits"
    }

    fun normalizePhoneNumber(phoneNumber: String?): String {
        return phoneNumber.orEmpty().filter { it.isDigit() }
    }

    /** True once the user has "verified" in test mode. */
    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    /** Marks the session active and returns the phone-derived account id. */
    fun signIn(context: Context, phoneNumber: String): String {
        val normalizedPhone = normalizePhoneNumber(phoneNumber)
        val uid = deriveAccountId(normalizedPhone)
        val existingProfileId = prefs(context).getString(KEY_PROFILE_ID, null)
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_UID, uid)
            .putString(KEY_PHONE, phoneNumber)
            .putString(KEY_NORMALIZED_PHONE, normalizedPhone)
            .putString(KEY_PROFILE_ID, existingProfileId ?: generateProfileId(context))
            .apply()

        // Keep the same persistent identity for the same normalized phone number across app restarts.
        val db = com.google.firebase.database.FirebaseDatabase.getInstance().reference
        db.child("users").child(uid).child("phoneNumber").setValue(phoneNumber)
        db.child("users").child(uid).child("normalizedPhone").setValue(normalizedPhone)
        db.child("users").child(uid).child("accountId").setValue(uid)
        return uid
    }

    /** Clears only the active flag, so the uid and cached profile survive a re-login. */
    fun signOut(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).apply()
    }

    fun phoneNumber(context: Context): String =
        prefs(context).getString(KEY_PHONE, "") ?: ""

    fun uid(context: Context): String =
        prefs(context).getString(KEY_UID, null) ?: "test_anonymous"

    fun setUserId(context: Context, uid: String) {
        prefs(context).edit().putString(KEY_UID, uid).apply()
    }

    fun profileId(context: Context): String {
        val existing = prefs(context).getString(KEY_PROFILE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val generated = generateProfileId(context)
        prefs(context).edit().putString(KEY_PROFILE_ID, generated).apply()
        return generated
    }

    private fun generateProfileId(context: Context): String {
        val existing = prefs(context).getString(KEY_PROFILE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = java.util.Random()
        var candidate: String
        do {
            candidate = (1..6).joinToString("") { chars[random.nextInt(chars.length)].toString() }
        } while (candidate == "anonymous" || candidate == "test123")

        return candidate
    }

    /**
     * The id to use for per-user data everywhere in the app: the real Firebase uid when one
     * exists, otherwise the local test account id (test mode only).
     */
    fun currentUserId(context: Context): String? {
        return if (AuthActivity.TEST_MODE) {
            uid(context)
        } else {
            FirebaseAuth.getInstance().currentUser?.uid
        }
    }

    // --- Local profile cache (used when Realtime Database is not reachable yet) ---

    private fun profileKey(accountId: String) = "$PROFILE_PREFIX$accountId"
    private fun profileDisplayNameKey(accountId: String) = "$PROFILE_DISPLAY_NAME_PREFIX$accountId"
    private fun profileGenderKey(accountId: String) = "$PROFILE_GENDER_PREFIX$accountId"
    private fun profileAgeKey(accountId: String) = "$PROFILE_AGE_PREFIX$accountId"
    private fun profileCityKey(accountId: String) = "$PROFILE_CITY_PREFIX$accountId"
    private fun profileAvatarKey(accountId: String) = "$PROFILE_AVATAR_PREFIX$accountId"

    fun cacheDisplayName(context: Context, userId: String, displayName: String) {
        prefs(context).edit().putString(profileKey(userId), displayName).apply()
        prefs(context).edit().putString(profileDisplayNameKey(userId), displayName).apply()
    }

    fun cachedDisplayName(context: Context, userId: String): String? =
        prefs(context).getString(profileKey(userId), null)

    fun cacheProfile(
        context: Context,
        userId: String,
        displayName: String?,
        gender: String?,
        age: Int?,
        city: String?,
        avatar: String?
    ) {
        val editor = prefs(context).edit()
        displayName?.let { editor.putString(profileDisplayNameKey(userId), it) }
        gender?.let { editor.putString(profileGenderKey(userId), it) }
        age?.let { editor.putInt(profileAgeKey(userId), it) }
        city?.let { editor.putString(profileCityKey(userId), it) }
        avatar?.let { editor.putString(profileAvatarKey(userId), it) }
        editor.apply()
    }

    fun cachedProfileDisplayName(context: Context, userId: String): String? =
        prefs(context).getString(profileDisplayNameKey(userId), null)

    fun cachedProfileGender(context: Context, userId: String): String? =
        prefs(context).getString(profileGenderKey(userId), null)

    fun cachedProfileAge(context: Context, userId: String): Int? {
        val age = prefs(context).getInt(profileAgeKey(userId), -1)
        return if (age >= 0) age else null
    }

    fun cachedProfileCity(context: Context, userId: String): String? =
        prefs(context).getString(profileCityKey(userId), null)

    fun cachedProfileAvatar(context: Context, userId: String): String? =
        prefs(context).getString(profileAvatarKey(userId), null)
}
