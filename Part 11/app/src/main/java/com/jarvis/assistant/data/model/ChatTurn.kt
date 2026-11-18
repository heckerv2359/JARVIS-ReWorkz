package com.jarvis.assistant.data.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ChatStatus {
    const val STREAMING = "streaming"
    const val ERROR = "error"
    const val STOPPED = "stopped"
}

data class ChatTurn(
    val id: String = UUID.randomUUID().toString(),
    val userTranscript: String,
    val jarvisResponse: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<String>? = null,
    val status: String? = null
) {
    val formattedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}
