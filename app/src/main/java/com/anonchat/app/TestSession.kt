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
    private const val PROFILE_PREFIX = "anonchat_test_profile_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Shared derivation: "test_" plus every digit of the full E.164 number. */
    fun deriveAccountId(phoneNumber: String?): String {
        val digits = phoneNumber.orEmpty().filter { it.isDigit() }
        return if (digits.isEmpty()) "test_anonymous" else "test_$digits"
    }

    /** True once the user has "verified" in test mode. */
    fun isActive(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ACTIVE, false)

    /** Marks the session active and returns the phone-derived account id. */
    fun signIn(context: Context, phoneNumber: String): String {
        val uid = deriveAccountId(phoneNumber)
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_UID, uid)
            .putString(KEY_PHONE, phoneNumber)
            .apply()
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

    /**
     * The id to use for per-user data everywhere in the app: the real Firebase uid when one
     * exists, otherwise the local test account id (test mode only).
     */
    fun currentUserId(context: Context): String? {
        FirebaseAuth.getInstance().currentUser?.uid?.let { return it }
        return if (AuthActivity.TEST_MODE) uid(context) else null
    }

    // --- Local profile cache (used when Realtime Database is not reachable yet) ---

    private fun profileKey(accountId: String) = "$PROFILE_PREFIX$accountId"

    fun cacheDisplayName(context: Context, userId: String, displayName: String) {
        prefs(context).edit().putString(profileKey(userId), displayName).apply()
    }

    fun cachedDisplayName(context: Context, userId: String): String? =
        prefs(context).getString(profileKey(userId), null)
}
