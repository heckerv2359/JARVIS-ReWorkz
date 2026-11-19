package com.jarvis.assistant.ui.chat

import android.content.Context
import android.os.SystemClock
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ChatStatus
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.pro.OfflineAssistant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the written chat. It lives outside the fragment, so a reply keeps streaming
 * when the user switches tabs.
 */
object ChatController {

    private const val HISTORY_TURNS = 24
    private const val ATTACHMENT_TURNS = 2
    private const val VOICE_USER_PLACEHOLDER = "(Audio query)"
    private const val VOICE_REPLY_PLACEHOLDER = "(Audio response)"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    fun isGenerating(): Boolean = _generating.value

    fun stop() {
        job?.cancel()
    }

    fun send(context: Context, text: String, attachments: List<String>) {
        if (_generating.value) return
        val app = JarvisApp.instance
        val repo = app.chatRepository
        val clean = text.trim()
        if (clean.isEmpty() && attachments.isEmpty()) return

        if (attachments.isEmpty() && handleMemoryCommand(clean)) return

        // Facts told in the chat (favorite song, birthday and so on) are learned for the voice side too.
        if (clean.isNotEmpty()) {
            try {
                app.memoryManager.learnPreferencesFromConversation(clean)
            } catch (e: Exception) {
                // learning is best effort
            }
        }

        val turn = repo.beginTurn(clean, attachments)
        run(context.applicationContext, turn.id, clean)
    }

    fun regenerate(context: Context, turnId: String) {
        if (_generating.value) return
        val repo = JarvisApp.instance.chatRepository
        val turn = repo.getTurn(turnId) ?: return
        val text = if (turn.userTranscript == VOICE_USER_PLACEHOLDER) "" else turn.userTranscript
        val attachments = turn.attachments.orEmpty()
        repo.removeFrom(turnId)
        send(context, text, attachments)
    }

    private fun handleMemoryCommand(text: String): Boolean {
        val lower = text.lowercase()
        val app = JarvisApp.instance
        val repo = app.chatRepository
        val action: suspend () -> String
        if (lower.startsWith("/remember ")) {
            val arg = text.substring(10).trim()
            action = { app.memoryManager.rememberFact(arg) }
        } else if (lower.startsWith("remember that ") || lower.startsWith("remember my ")) {
            val arg = text.trim()
            action = { app.memoryManager.rememberFact(arg) }
        } else if (lower.startsWith("/forget ")) {
            val arg = text.substring(8).trim()
            action = { app.memoryManager.forgetFact(arg) }
        } else if (lower == "what do you remember" || lower == "what do you remember about me") {
            action = { app.memoryManager.getWhatDoYouRemember() }
        } else {
            return false
        }
        scope.launch {
            val reply = try { action() } catch (e: Exception) { "I could not update my memory right now." }
            repo.addTurn(text, reply)
        }
        return true
    }

    private fun run(appContext: Context, turnId: String, userText: String) {
        _generating.value = true
        val app = JarvisApp.instance
        val repo = app.chatRepository
        job = scope.launch {
            val acc = StringBuilder()
            try {
                val system = ChatPromptBuilder.build()
                val body = withContext(Dispatchers.IO) { buildBody(turnId, system) }
                var lastPush = 0L
                val result = TextChatEngine.stream(app.preferences.apiKey, body, ChatPrefs.model) { delta ->
                    acc.append(delta)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPush > 70L) {
                        lastPush = now
                        repo.updateTurn(turnId, acc.toString(), ChatStatus.STREAMING, false)
                    }
                }
                finish(appContext, turnId, userText, result)
            } catch (e: CancellationException) {
                repo.updateTurn(turnId, acc.toString().trim(), ChatStatus.STOPPED, true)
                throw e
            } catch (e: Exception) {
                val partial = acc.toString().trim()
                repo.updateTurn(
                    turnId,
                    if (partial.isEmpty()) "Something went wrong. Tap Retry." else partial,
                    ChatStatus.ERROR,
                    true
                )
            } finally {
                _generating.value = false
            }
        }
    }

    private fun finish(appContext: Context, turnId: String, userText: String, result: TextChatEngine.Result) {
        val repo = JarvisApp.instance.chatRepository
        val text = result.text.trim()
        if (result.error == null) {
            var finalText = text
            if (result.sources.isNotEmpty()) {
                finalText += "\n\n**Sources**\n" + result.sources.take(6).joinToString("\n") { s ->
                    "- [" + s.title.replace("[", "(").replace("]", ")") + "](" + s.url + ")"
                }
            }
            repo.updateTurn(turnId, finalText, null, true)
            if (ChatPrefs.autoSpeak) ChatSpeaker.speak(appContext, text)
            return
        }

        if (result.kind == TextChatEngine.KIND_NETWORK && text.isEmpty() && ChatPrefs.offlineFallback && userText.isNotBlank()) {
            val offline = try { OfflineAssistant.handle(appContext, userText) } catch (e: Exception) { "" }
            if (offline.isNotBlank() && !offline.startsWith("Sorry")) {
                repo.updateTurn(turnId, offline + "\n\n(Offline mode, no internet)", null, true)
                return
            }
        }

        val friendly = when (result.kind) {
            TextChatEngine.KIND_NO_KEY -> "No Gemini API key is saved. Add it in Settings, then tap Retry."
            TextChatEngine.KIND_KEY -> "Google rejected the Gemini API key. Check it in Settings, then tap Retry."
            TextChatEngine.KIND_NETWORK -> "I could not reach Gemini. Check your internet connection and tap Retry."
            TextChatEngine.KIND_BLOCKED -> result.error ?: "The request was blocked."
            else -> "Gemini did not answer. " + result.error
        }
        val shown = if (text.isNotEmpty()) text + "\n\n" + friendly else friendly
        repo.updateTurn(turnId, shown, ChatStatus.ERROR, true)
    }

    /** Builds the request from the conversation so far, including the turn being answered. */
    private fun buildBody(turnId: String, system: String): JSONObject {
        val app = JarvisApp.instance
        val all = app.chatRepository.turnsSnapshot()
        val idx = all.indexOfFirst { it.id == turnId }
        val upTo: List<ChatTurn> = if (idx >= 0) all.subList(0, idx + 1) else all
        val usable = upTo.takeLast(HISTORY_TURNS)

        val withMedia = HashSet<String>()
        usable.filter { !it.attachments.isNullOrEmpty() }.takeLast(ATTACHMENT_TURNS).forEach { withMedia.add(it.id) }

        val contents = JSONArray()
        for (t in usable) {
            val isCurrent = t.id == turnId
            if (!isCurrent && (t.status == ChatStatus.ERROR || t.status == ChatStatus.STREAMING)) continue

            val parts = JSONArray()
            val userText = if (t.userTranscript == VOICE_USER_PLACEHOLDER) "" else t.userTranscript
            if (userText.isNotBlank()) parts.put(JSONObject().put("text", userText))
            if (t.id in withMedia) {
                t.attachments?.forEach { path -> ChatMedia.toParts(path).forEach { parts.put(it) } }
            } else if (!t.attachments.isNullOrEmpty()) {
                parts.put(JSONObject().put("text", "[The user attached " + (t.attachments?.size ?: 0) + " file(s) earlier in this chat.]"))
            }
            if (parts.length() == 0) {
                parts.put(JSONObject().put("text", if (t.attachments.isNullOrEmpty()) "(voice message)" else "What is in the attached file?"))
            }
            contents.put(JSONObject().put("role", "user").put("parts", parts))

            if (!isCurrent) {
                val reply = t.jarvisResponse.trim()
                if (reply.isNotEmpty() && reply != VOICE_REPLY_PLACEHOLDER) {
                    contents.put(
                        JSONObject().put("role", "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", reply)))
                    )
                }
            }
        }

        val maxTokens = when (ChatPrefs.length) {
            ChatPrefs.LENGTH_SHORT -> 1024
            ChatPrefs.LENGTH_DETAILED -> 8192
            else -> 4096
        }
        val body = JSONObject()
        body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        body.put("contents", contents)
        body.put(
            "generationConfig",
            JSONObject()
                .put("temperature", ChatPrefs.temperature.toDouble())
                .put("maxOutputTokens", maxTokens)
        )
        if (ChatPrefs.searchOn || ChatPrefs.mode == ChatPrefs.MODE_SEARCH) {
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        return body
    }
}
