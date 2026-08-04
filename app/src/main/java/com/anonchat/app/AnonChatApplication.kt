package com.anonchat.app

import android.app.Application
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging

class AnonChatApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Enable Firebase RTDB disk persistence for offline support.
        // Must be called before any other FirebaseDatabase usage.
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)

        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                task.result?.let { token -> FcmNotifications.registerToken(this, token) }
            }
        }
    }
}
