package com.anonchat.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * WelcomeActivity acts as a splash/router screen.
 * - If authenticated: navigate directly to ChatListActivity
 * - If not authenticated: navigate to AuthActivity
 * - 10-second timeout on session validation treats as no session
 */
class WelcomeActivity : AppCompatActivity() {

    private var hasNavigated = false
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        // Timeout expired — treat as no valid session
        navigateToAuth()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (AuthActivity.TEST_MODE) {
            // Test mode: OTP is bypassed, so route on the local session instead of Firebase Auth.
            cancelTimeout()
            if (TestSession.isActive(this)) {
                navigateToChatList()
            } else {
                navigateToAuth()
            }
            return
        }

        // Start 10-second timeout for session validation
        timeoutHandler.postDelayed(timeoutRunnable, 10_000L)

        checkAuthState()
    }

    private fun checkAuthState() {
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser != null) {
            // User is authenticated — reload to validate token freshness
            currentUser.reload()
                .addOnCompleteListener { task ->
                    cancelTimeout()
                    if (task.isSuccessful && FirebaseAuth.getInstance().currentUser != null) {
                        navigateToChatList()
                    } else {
                        // Token invalid or revoked
                        FirebaseAuth.getInstance().signOut()
                        navigateToAuth()
                    }
                }
        } else {
            // No authenticated user
            cancelTimeout()
            navigateToAuth()
        }
    }

    private fun navigateToChatList() {
        if (hasNavigated) return
        hasNavigated = true

        val intent = Intent(this, ChatListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun navigateToAuth() {
        if (hasNavigated) return
        hasNavigated = true

        val intent = Intent(this, AuthActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun cancelTimeout() {
        timeoutHandler.removeCallbacks(timeoutRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTimeout()
    }
}
