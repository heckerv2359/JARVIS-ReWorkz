package com.jarvis.assistant.ui.chat

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Reads written replies aloud with Android text to speech. Only used when the user asks for it. */
object ChatSpeaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(context: Context, text: String) {
        val clean = MarkdownRenderer.stripForSpeech(text).take(3900)
        if (clean.isBlank()) return
        if (tts == null) {
            pending = clean
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.setLanguage(Locale.getDefault())
                    ready = true
                    pending?.let { speakNow(it) }
                    pending = null
                }
            }
        } else if (ready) {
            speakNow(clean)
        } else {
            pending = clean
        }
    }

    private fun speakNow(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis_chat")
    }

    fun stop() {
        try { tts?.stop() } catch (e: Exception) { /* ignore */ }
    }

    fun isSpeaking(): Boolean = try { tts?.isSpeaking == true } catch (e: Exception) { false }
}
