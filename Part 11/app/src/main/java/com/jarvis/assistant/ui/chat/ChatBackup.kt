package com.jarvis.assistant.ui.chat

import com.jarvis.assistant.JarvisApp
import org.json.JSONObject

/** Builds and applies the backup that lets you restore chats and the API key after a reinstall. */
object ChatBackup {

    fun buildPayload(): String {
        val app = JarvisApp.instance
        val p = app.preferences
        val chatPrefs = JSONObject()
            .put("model", ChatPrefs.model)
            .put("length", ChatPrefs.length)
            .put("temperature", ChatPrefs.temperature.toDouble())
            .put("searchOn", ChatPrefs.searchOn)
            .put("autoSpeak", ChatPrefs.autoSpeak)
            .put("offlineFallback", ChatPrefs.offlineFallback)
            .put("customInstructions", ChatPrefs.customInstructions)
            .put("languageOverride", ChatPrefs.languageOverride)
            .put("mode", ChatPrefs.mode)
        return JSONObject()
            .put("apiKey", p.apiKey)
            .put("userName", p.userName)
            .put("personality", p.personality)
            .put("chatPrefs", chatPrefs)
            .put("chats", app.chatRepository.exportAllJson())
            .toString()
    }

    /** Applies a decrypted backup and returns a short summary for the user. */
    fun applyPayload(json: String): String {
        val app = JarvisApp.instance
        val p = app.preferences
        val o = JSONObject(json)

        var restoredKey = false
        val key = o.optString("apiKey", "")
        if (key.isNotBlank()) {
            p.apiKey = key
            restoredKey = true
        }
        val name = o.optString("userName", "")
        if (name.isNotBlank()) p.userName = name
        val persona = o.optString("personality", "")
        if (persona.isNotBlank()) p.personality = persona

        val cp = o.optJSONObject("chatPrefs")
        if (cp != null) {
            ChatPrefs.model = cp.optString("model", ChatPrefs.model)
            ChatPrefs.length = cp.optInt("length", ChatPrefs.length)
            ChatPrefs.temperature = cp.optDouble("temperature", ChatPrefs.temperature.toDouble()).toFloat()
            ChatPrefs.searchOn = cp.optBoolean("searchOn", ChatPrefs.searchOn)
            ChatPrefs.autoSpeak = cp.optBoolean("autoSpeak", ChatPrefs.autoSpeak)
            ChatPrefs.offlineFallback = cp.optBoolean("offlineFallback", ChatPrefs.offlineFallback)
            ChatPrefs.customInstructions = cp.optString("customInstructions", ChatPrefs.customInstructions)
            ChatPrefs.languageOverride = cp.optString("languageOverride", ChatPrefs.languageOverride)
            ChatPrefs.mode = cp.optInt("mode", ChatPrefs.mode)
        }

        val added = app.chatRepository.importAllJson(o.optString("chats", ""))
        val keyText = if (restoredKey) "API key restored. " else "No API key in this backup. "
        return keyText + added + " chat(s) added."
    }
}
