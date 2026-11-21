package com.jarvis.assistant.ui.chat

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Streams a written reply from Gemini over plain HTTPS. No microphone, no audio, no live session. */
object TextChatEngine {

    private const val TAG = "TextChatEngine"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"
    private const val MAX_ATTEMPTS = 4
    private const val MAX_TIMEOUTS = 2

    const val KIND_NO_KEY = "NO_KEY"
    const val KIND_KEY = "KEY"
    const val KIND_NETWORK = "NETWORK"
    const val KIND_BLOCKED = "BLOCKED"
    const val KIND_FAILED = "FAILED"

    class Source(val title: String, val url: String)
    class Result(val text: String, val sources: List<Source>, val error: String?, val kind: String?)

    private class Attempt(
        val ok: Boolean,
        val fatalKind: String?,
        val message: String,
        val network: Boolean,
        val partial: Boolean
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private val MODELS = listOf("gemini-flash-latest", "gemini-3.8-flash", "gemini-flash-lite-latest", "gemini-3.5-flash-lite")

    @Volatile private var lastGood: String? = null

    suspend fun stream(
        apiKeyRaw: String,
        body: JSONObject,
        preferredModel: String,
        onDelta: (String) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val apiKey = apiKeyRaw.trim()
        if (apiKey.isEmpty()) {
            return@withContext Result("", emptyList(), "No Gemini API key is saved. Add it in Settings.", KIND_NO_KEY)
        }
        val payload: RequestBody = body.toString().toRequestBody("application/json".toMediaType())

        val queue = ArrayDeque<String>()
        if (preferredModel.isNotBlank()) queue.addLast(preferredModel)
        lastGood?.let { queue.addLast(it) }
        MODELS.forEach { queue.addLast(it) }

        val tried = HashSet<String>()
        val notes = ArrayList<String>()
        var allNetwork = true
        var attempts = 0
        var timeouts = 0

        while (queue.isNotEmpty() && attempts < MAX_ATTEMPTS) {
            ensureActive()
            val model = queue.removeFirst()
            if (!tried.add(model)) continue
            attempts++

            val text = StringBuilder()
            val sources = LinkedHashMap<String, String>()
            val attempt = attemptOnce(model, apiKey, payload, text, sources, onDelta)
            val sourceList = sources.map { Source(it.value, it.key) }

            if (attempt.ok) {
                lastGood = model
                return@withContext Result(text.toString(), sourceList, null, null)
            }
            if (attempt.fatalKind != null) {
                return@withContext Result(text.toString(), sourceList, attempt.message, attempt.fatalKind)
            }
            if (attempt.partial) {
                return@withContext Result(text.toString(), sourceList, attempt.message, KIND_FAILED)
            }
            notes.add(model + ": " + attempt.message)
            if (!attempt.network) allNetwork = false
            if (attempt.message.startsWith("SocketTimeoutException")) {
                timeouts++
                if (timeouts >= MAX_TIMEOUTS) break
            }
        }

        val kind = if (allNetwork && notes.isNotEmpty()) KIND_NETWORK else KIND_FAILED
        val message = notes.joinToString("; ").take(400).ifEmpty { "Could not reach Gemini." }
        Result("", emptyList(), message, kind)
    }

    private suspend fun attemptOnce(
        model: String,
        apiKey: String,
        payload: RequestBody,
        text: StringBuilder,
        sources: MutableMap<String, String>,
        onDelta: (String) -> Unit
    ): Attempt = coroutineScope {
        val request = Request.Builder()
            .url(BASE + "models/" + model + ":streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(payload)
            .build()
        val call = client.newCall(request)

        // Cancelling the coroutine must abort a call that is blocked on the network right away,
        // otherwise Stop would only take effect after the read timeout.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = errorMessage(response.body?.string().orEmpty())
                    Log.w(TAG, model + " HTTP " + response.code + ": " + msg)
                    val kind = when (response.code) {
                        401, 403 -> KIND_KEY
                        400 -> if (msg.contains("API key", ignoreCase = true)) KIND_KEY else null
                        else -> null
                    }
                    return@use Attempt(false, kind, "HTTP " + response.code + ": " + msg, false, false)
                }
                val stream = response.body?.byteStream()
                    ?: return@use Attempt(false, null, "empty response", false, false)
                val reader = stream.bufferedReader()
                var blockReason = ""
                var finish = ""
                while (true) {
                    ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val json = try { JSONObject(data) } catch (e: Exception) { continue }

                    val candidates = json.optJSONArray("candidates")
                    if (candidates == null || candidates.length() == 0) {
                        val reason = json.optJSONObject("promptFeedback")?.optString("blockReason", "").orEmpty()
                        if (reason.isNotEmpty()) blockReason = reason
                        continue
                    }
                    val cand = candidates.optJSONObject(0) ?: continue
                    val parts = cand.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            if (part.optBoolean("thought", false)) continue
                            val t = part.optString("text", "")
                            if (t.isNotEmpty()) {
                                text.append(t)
                                onDelta(t)
                            }
                        }
                    }
                    val fr = cand.optString("finishReason", "")
                    if (fr.isNotEmpty()) finish = fr
                    val chunks = cand.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
                    if (chunks != null) {
                        for (i in 0 until chunks.length()) {
                            val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                            val uri = web.optString("uri", "")
                            if (uri.isNotEmpty() && !sources.containsKey(uri)) {
                                sources[uri] = web.optString("title", "").ifBlank { uri }
                            }
                        }
                    }
                }
                if (text.isEmpty()) {
                    return@use when {
                        blockReason.isNotEmpty() ->
                            Attempt(false, KIND_BLOCKED, "The request was blocked (" + blockReason + ").", false, false)
                        finish == "SAFETY" || finish == "PROHIBITED_CONTENT" || finish == "BLOCKLIST" ->
                            Attempt(false, KIND_BLOCKED, "The reply was blocked by Google's safety filters.", false, false)
                        else -> Attempt(false, null, "No answer came back.", false, false)
                    }
                }
                Attempt(true, null, "", false, false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (!isActive) throw CancellationException("stopped")
            Log.w(TAG, model + " failed: " + e.javaClass.simpleName + " " + e.message)
            Attempt(false, null, e.javaClass.simpleName + ": " + (e.message ?: "").take(80), true, text.isNotEmpty())
        } finally {
            watcher.cancel()
        }
    }

    private fun errorMessage(raw: String): String {
        return try {
            JSONObject(raw).optJSONObject("error")?.optString("message", "").orEmpty().ifBlank { raw.take(160) }.take(180)
        } catch (e: Exception) {
            raw.take(160)
        }
    }
}
