package com.anonchat.app

import android.app.Application
import android.content.Context
import com.google.firebase.database.FirebaseDatabase

class AnonChatApplication : Application() {

    override fun attachBaseContext(base: Context) {
        val lang = LocaleHelper.getLanguage(base)
        super.attachBaseContext(LocaleHelper.setLocale(base, lang))
    }

    override fun onCreate() {
        super.onCreate()
        // Enable Firebase RTDB disk persistence for offline support.
        // Must be called before any other FirebaseDatabase usage.
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)

        // Apply user-selected color/dark theme
        ThemeManager.applyTheme(this)
    }
}
