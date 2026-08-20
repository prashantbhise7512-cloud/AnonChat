package com.anonchat.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.adapter.LanguageAdapter
import com.google.android.material.button.MaterialButton

class LanguageSelectionActivity : AppCompatActivity() {

    private var selectedLanguageCode: String = "en"

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply active theme
        ThemeManager.applyTheme(this)

        setContentView(R.layout.activity_language_selection)

        selectedLanguageCode = LocaleHelper.getLanguage(this)

        val recyclerLanguages = findViewById<RecyclerView>(R.id.recyclerLanguages)
        val btnContinue = findViewById<MaterialButton>(R.id.btnContinueLanguage)

        // Apply dynamic primary theme color to button
        val primaryColor = ThemeManager.getPrimaryColor(this)
        btnContinue.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)

        val languages = LocaleHelper.getSupportedLanguages()
        val adapter = LanguageAdapter(languages, selectedLanguageCode) { lang ->
            selectedLanguageCode = lang.code
        }

        recyclerLanguages.layoutManager = GridLayoutManager(this, 2)
        recyclerLanguages.adapter = adapter

        val isFromProfile = intent.getBooleanExtra("EXTRA_FROM_PROFILE", false)
        val llProfileActions = findViewById<android.widget.LinearLayout>(R.id.llProfileActions)
        val btnSave = findViewById<MaterialButton>(R.id.btnSaveLanguage)
        val btnCancel = findViewById<MaterialButton>(R.id.btnCancelLanguage)

        if (isFromProfile) {
            btnContinue.visibility = android.view.View.GONE
            llProfileActions?.visibility = android.view.View.VISIBLE
            btnSave?.backgroundTintList = android.content.res.ColorStateList.valueOf(primaryColor)

            btnSave?.setOnClickListener {
                LocaleHelper.setLocale(this, selectedLanguageCode)
                getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                    .edit().putBoolean("has_selected_language", true).apply()
                finish()
            }

            btnCancel?.setOnClickListener {
                finish()
            }
        } else {
            btnContinue.visibility = android.view.View.VISIBLE
            llProfileActions?.visibility = android.view.View.GONE

            btnContinue.setOnClickListener {
                LocaleHelper.setLocale(this, selectedLanguageCode)
                getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
                    .edit().putBoolean("has_selected_language", true).apply()

                val currentUserId = TestSession.currentUserId(this)
                val nextIntent = if (!currentUserId.isNullOrBlank()) {
                    Intent(this, ChatListActivity::class.java)
                } else {
                    Intent(this, WelcomeActivity::class.java)
                }
                startActivity(nextIntent)
                finish()
            }
        }
    }
}
