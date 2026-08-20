package com.anonchat.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.anonchat.app.model.Language
import java.util.Locale

object LocaleHelper {

    private const val PREF_SELECTED_LANGUAGE = "app_language"

    fun getSupportedLanguages(): List<Language> {
        return listOf(
            Language("en", "English", "English"),
            Language("hi", "हिन्दी", "Hindi"),
            Language("mr", "मराठी", "Marathi"),
            Language("kn", "ಕನ್ನಡ", "Kannada"),
            Language("ta", "தமிழ்", "Tamil"),
            Language("te", "తెలుగు", "Telugu"),
            Language("ml", "മലയാളം", "Malayalam"),
            Language("gu", "ગુજરાતી", "Gujarati"),
            Language("raj", "राजस्थानी", "Rajasthani"),
            Language("bho", "भोजपुरी", "Bhojpuri"),
            Language("pa", "ਪੰਜਾਬੀ", "Punjabi"),
            Language("har", "हरियाणवी", "Haryanvi"),
            Language("bn", "বাংলা", "Bengali"),
            Language("ur", "اردو", "Urdu")
        )
    }

    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences("anonchat_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_SELECTED_LANGUAGE, "en") ?: "en"
    }

    fun setLocale(context: Context, languageCode: String): Context {
        persistLanguage(context, languageCode)
        return updateResources(context, languageCode)
    }

    private fun persistLanguage(context: Context, languageCode: String) {
        val prefs = context.getSharedPreferences("anonchat_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SELECTED_LANGUAGE, languageCode).apply()
    }

    private fun updateResources(context: Context, languageCode: String): Context {
        // Map regional languages to base locale for Android system fallback where needed
        val sysLang = when (languageCode) {
            "raj", "bho", "har" -> "hi"
            else -> languageCode
        }

        val locale = Locale(sysLang)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
