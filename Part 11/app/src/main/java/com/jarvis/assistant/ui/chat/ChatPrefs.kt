package com.jarvis.assistant.ui.chat

import android.content.Context
import com.jarvis.assistant.JarvisApp

/** Settings that only affect the written chat. The voice assistant keeps its own settings. */
object ChatPrefs {

    const val MODE_CHAT = 0
    const val MODE_CODE = 1
    const val MODE_SEARCH = 2
    const val MODE_THINK = 3

    const val LENGTH_SHORT = 0
    const val LENGTH_NORMAL = 1
    const val LENGTH_DETAILED = 2

    private fun sp() = JarvisApp.instance.getSharedPreferences("jarvis_chat_prefs", Context.MODE_PRIVATE)

    var model: String
        get() = sp().getString("model", "") ?: ""
        set(v) = sp().edit().putString("model", v.trim()).apply()

    var length: Int
        get() = sp().getInt("length", LENGTH_NORMAL)
        set(v) = sp().edit().putInt("length", v).apply()

    var temperature: Float
        get() = sp().getFloat("temperature", 0.7f)
        set(v) = sp().edit().putFloat("temperature", v).apply()

    var searchOn: Boolean
        get() = sp().getBoolean("search_on", false)
        set(v) = sp().edit().putBoolean("search_on", v).apply()

    var autoSpeak: Boolean
        get() = sp().getBoolean("auto_speak", false)
        set(v) = sp().edit().putBoolean("auto_speak", v).apply()

    var offlineFallback: Boolean
        get() = sp().getBoolean("offline_fallback", true)
        set(v) = sp().edit().putBoolean("offline_fallback", v).apply()

    var customInstructions: String
        get() = sp().getString("custom_instructions", "") ?: ""
        set(v) = sp().edit().putString("custom_instructions", v.trim()).apply()

    var languageOverride: String
        get() = sp().getString("language_override", "") ?: ""
        set(v) = sp().edit().putString("language_override", v.trim()).apply()

    var mode: Int
        get() = sp().getInt("mode", MODE_CHAT)
        set(v) = sp().edit().putInt("mode", v).apply()

    fun modeLabel(mode: Int = this.mode): String = when (mode) {
        MODE_CODE -> "Code"
        MODE_SEARCH -> "Search"
        MODE_THINK -> "Think"
        else -> "Chat"
    }
}
