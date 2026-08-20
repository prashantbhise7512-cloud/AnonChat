package com.anonchat.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {

    const val THEME_BLUE = "blue"
    const val THEME_GREEN = "green"
    const val THEME_PURPLE = "purple"
    const val THEME_RED = "red"
    const val THEME_TEAL = "teal"
    const val THEME_DARK = "dark"

    private const val PREF_KEY_THEME = "app_theme_color"

    fun getSelectedTheme(context: Context): String {
        val prefs = context.getSharedPreferences("anonchat_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_KEY_THEME, THEME_BLUE) ?: THEME_BLUE
    }

    fun setSelectedTheme(context: Context, themeKey: String) {
        val prefs = context.getSharedPreferences("anonchat_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_THEME, themeKey).apply()
    }

    fun applyTheme(context: Context) {
        val themeKey = getSelectedTheme(context)
        val themeResId = when (themeKey) {
            THEME_GREEN -> R.style.Theme_AnonChat_Green
            THEME_PURPLE -> R.style.Theme_AnonChat_Purple
            THEME_RED -> R.style.Theme_AnonChat_Red
            THEME_TEAL -> R.style.Theme_AnonChat_Teal
            THEME_DARK -> R.style.Theme_AnonChat_Dark
            else -> R.style.Theme_AnonChat
        }
        if (context is Activity) {
            context.setTheme(themeResId)
        }

        if (themeKey == THEME_DARK) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        }
    }

    fun getPrimaryColorHex(themeKey: String): String {
        return when (themeKey) {
            THEME_GREEN -> "#2E7D32"
            THEME_PURPLE -> "#7B1FA2"
            THEME_RED -> "#C62828"
            THEME_TEAL -> "#00695C"
            THEME_DARK -> "#212121"
            else -> "#1B72C0"
        }
    }

    fun getPrimaryColor(context: Context): Int {
        val themeKey = getSelectedTheme(context)
        return Color.parseColor(getPrimaryColorHex(themeKey))
    }
}
