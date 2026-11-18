package com.jarvis.assistant.data.repository

import com.google.gson.Gson
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.data.model.ChatStatus
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.Conversation
import com.jarvis.assistant.data.model.ConversationInfo
import com.jarvis.assistant.data.model.StoredChats
import com.jarvis.assistant.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.Executors

/**
 * Holds every conversation. [turns] always shows the active conversation, so the voice service
 * (which calls [addTurn]) and the text chat share the same API.
 */
class ChatRepository(private val preferences: AppPreferences) {

    companion object {
        const val DEFAULT_TITLE = "New chat"
        private const val MAX_TURNS = 300
        private const val VOICE_USER_PLACEHOLDER = "(Audio query)"
        private const val VOICE_REPLY_PLACEHOLDER = "(Audio response)"
    }

    private val gson = Gson()
    private val io = Executors.newSingleThreadExecutor()
    private val stateFile: File by lazy { File(JarvisApp.instance.filesDir, "conversations.json") }
    private val conversations = ArrayList<Conversation>()
    private var activeId: String = ""

    private val _turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val turns: StateFlow<List<ChatTurn>> = _turns.asStateFlow()

    private val _title = MutableStateFlow(DEFAULT_TITLE)
    val title: StateFlow<String> = _title.asStateFlow()

    init {
        load()
    }

    private fun load() {
        val loaded: StoredChats? = try {
            if (stateFile.exists()) gson.fromJson(stateFile.readText(), StoredChats::class.java) else null
        } catch (e: Exception) {
            null
        }
        if (loaded != null && loaded.conversations.isNotEmpty()) {
            conversations.addAll(loaded.conversations)
            activeId = if (conversations.any { it.id == loaded.activeId }) loaded.activeId else conversations.first().id
        } else {
            val legacy = preferences.loadChatHistory()
            val first = Conversation(
                title = if (legacy.isEmpty()) DEFAULT_TITLE else "Earlier chat",
                turns = legacy
            )
            conversations.add(first)
            activeId = first.id
            if (legacy.isNotEmpty()) save()
        }
        publish()
    }

    private fun active(): Conversation =
        conversations.firstOrNull { it.id == activeId } ?: conversations.first()

    private fun publish() {
        val a = active()
        _turns.value = a.turns
        _title.value = a.title
    }

    private fun save() {
        val json = try {
            gson.toJson(StoredChats(activeId, ArrayList(conversations)))
        } catch (e: Exception) {
            return
        }
        io.execute {
            try {
                val tmp = File(stateFile.parentFile, "conversations.json.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(stateFile)) {
                    stateFile.writeText(json)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // best effort
            }
        }
    }

    private fun titleFrom(text: String): String {
        val one = text.replace("\n", " ").trim()
        return if (one.length > 40) one.take(40).trim() + "..." else one
    }

    private fun appendTurn(c: Conversation, turn: ChatTurn) {
        c.turns = (c.turns + turn).takeLast(MAX_TURNS)
        c.updatedAt = System.currentTimeMillis()
        if (c.title == DEFAULT_TITLE && turn.userTranscript.isNotBlank() && turn.userTranscript != VOICE_USER_PLACEHOLDER) {
            c.title = titleFrom(turn.userTranscript)
        }
        if (c.id == activeId) publish()
        save()
    }

    @Synchronized
    fun addTurn(userText: String, jarvisText: String) {
        val cleanUser = userText.trim()
        val cleanJarvis = jarvisText.trim()
        if (cleanUser.isEmpty() && cleanJarvis.isEmpty()) return
        val c = active()
        val last = c.turns.lastOrNull()
        if (last != null && last.userTranscript == cleanUser && last.jarvisResponse == cleanJarvis) return
        appendTurn(
            c,
            ChatTurn(
                userTranscript = cleanUser.ifEmpty { VOICE_USER_PLACEHOLDER },
                jarvisResponse = cleanJarvis.ifEmpty { VOICE_REPLY_PLACEHOLDER }
            )
        )
    }

    @Synchronized
    fun beginTurn(userText: String, attachments: List<String>): ChatTurn {
        val turn = ChatTurn(
            userTranscript = userText.trim(),
            jarvisResponse = "",
            attachments = if (attachments.isEmpty()) null else attachments,
            status = ChatStatus.STREAMING
        )
        appendTurn(active(), turn)
        return turn
    }

    @Synchronized
    fun updateTurn(id: String, response: String, status: String?, persist: Boolean) {
        for (c in conversations) {
            val idx = c.turns.indexOfFirst { it.id == id }
            if (idx < 0) continue
            val list = c.turns.toMutableList()
            list[idx] = list[idx].copy(jarvisResponse = response, status = status)
            c.turns = list
            if (persist) {
                c.updatedAt = System.currentTimeMillis()
                save()
            }
            if (c.id == activeId) publish()
            return
        }
    }

    /** Removes this turn and every turn after it. Attachment files are kept so a resend can reuse them. */
    @Synchronized
    fun removeFrom(id: String) {
        for (c in conversations) {
            val idx = c.turns.indexOfFirst { it.id == id }
            if (idx < 0) continue
            c.turns = c.turns.subList(0, idx).toList()
            if (c.id == activeId) publish()
            save()
            return
        }
    }

    @Synchronized
    fun getTurn(id: String): ChatTurn? {
        for (c in conversations) {
            val t = c.turns.firstOrNull { it.id == id }
            if (t != null) return t
        }
        return null
    }

    @Synchronized
    fun turnsSnapshot(): List<ChatTurn> = active().turns

    @Synchronized
    fun clearHistory() {
        val c = active()
        deleteMedia(c.turns)
        c.turns = emptyList()
        c.title = DEFAULT_TITLE
        preferences.clearChatHistory()
        publish()
        save()
    }

    // ---- conversation management ----

    @Synchronized
    fun activeConversationId(): String = activeId

    @Synchronized
    fun newConversation() {
        if (active().turns.isEmpty()) return
        val c = Conversation()
        conversations.add(c)
        activeId = c.id
        publish()
        save()
    }

    @Synchronized
    fun switchTo(id: String) {
        if (conversations.none { it.id == id }) return
        activeId = id
        publish()
        save()
    }

    @Synchronized
    fun rename(id: String, newTitle: String) {
        val c = conversations.firstOrNull { it.id == id } ?: return
        val t = newTitle.trim()
        if (t.isEmpty()) return
        c.title = t.take(60)
        if (c.id == activeId) publish()
        save()
    }

    @Synchronized
    fun togglePin(id: String) {
        val c = conversations.firstOrNull { it.id == id } ?: return
        c.pinned = !c.pinned
        save()
    }

    @Synchronized
    fun delete(id: String) {
        val c = conversations.firstOrNull { it.id == id } ?: return
        deleteMedia(c.turns)
        conversations.remove(c)
        if (conversations.isEmpty()) {
            val fresh = Conversation()
            conversations.add(fresh)
            activeId = fresh.id
        } else if (id == activeId) {
            activeId = conversations.maxByOrNull { it.updatedAt }!!.id
        }
        publish()
        save()
    }

    @Synchronized
    fun listConversations(query: String = ""): List<ConversationInfo> {
        val q = query.trim()
        return conversations
            .filter { c ->
                q.isEmpty() || c.title.contains(q, true) ||
                    c.turns.any { it.userTranscript.contains(q, true) || it.jarvisResponse.contains(q, true) }
            }
            .sortedWith(compareByDescending<Conversation> { it.pinned }.thenByDescending { it.updatedAt })
            .map { c ->
                val last = c.turns.lastOrNull()
                val preview = if (last == null) "" else last.jarvisResponse.ifBlank { last.userTranscript }
                ConversationInfo(c.id, c.title, c.pinned, c.updatedAt, c.turns.size, preview.replace("\n", " ").take(80))
            }
    }

    @Synchronized
    fun exportText(id: String): String {
        val c = conversations.firstOrNull { it.id == id } ?: return ""
        val sb = StringBuilder()
        sb.append(c.title).append("\n\n")
        for (t in c.turns) {
            if (t.userTranscript.isNotBlank()) sb.append("You: ").append(t.userTranscript).append("\n\n")
            if (t.jarvisResponse.isNotBlank()) sb.append("Jarvis: ").append(t.jarvisResponse).append("\n\n")
        }
        return sb.toString().trim()
    }

    private fun deleteMedia(list: List<ChatTurn>) {
        for (t in list) {
            t.attachments?.forEach { path ->
                try { File(path).delete() } catch (e: Exception) { /* ignore */ }
            }
        }
    }
}
