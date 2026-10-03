package dev.r1ptt.net

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ChatClient {
    /**
     * Streams a reply. [onDelta] gets text as it arrives (on the calling thread); [onStatus] gets
     * agent progress hints (Hermes tool events). Returns the whole reply.
     */
    fun stream(
        req: ChatRequest.Built,
        calls: CallRegistry,
        onDelta: (String) -> Unit,
        onStatus: (String) -> Unit,
    ): String {
        val http = Request.Builder().url(req.url)
            .apply { req.headers.forEach { (k, v) -> header(k, v) } }
            .post(req.body.toString().toRequestBody(Http.JSON))
            .build()
        val call = Http.withReadTimeout(req.timeoutSec).newCall(http)
        calls.add(call)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw ApiError(resp.code, errorMessage(resp.body?.string().orEmpty()))
            val body = resp.body ?: throw IOException("Empty response")
            val full = StringBuilder()

            if (resp.header("Content-Type").orEmpty().contains("application/json")) {
                // The server ignored stream=true.
                val text = ChatChunk.fullMessage(body.string())
                full.append(text)
                onDelta(text)
                return full.toString()
            }

            var done = false
            val parser = SseParser { event, data ->
                if (event != null && event.startsWith("hermes.")) {
                    onStatus(progressHint(data))
                    return@SseParser
                }
                when (val c = ChatChunk.parse(data)) {
                    is ChatChunk.Text -> {
                        full.append(c.delta)
                        onDelta(c.delta)
                    }
                    is ChatChunk.Failed -> throw IOException(c.message)
                    ChatChunk.Done -> done = true
                    ChatChunk.Other -> {}
                }
            }
            val src = body.source()
            while (!done) {
                val line = src.readUtf8Line() ?: break
                parser.line(line)
            }
            parser.finish()
            return full.toString()
        }
    }

    private fun progressHint(data: String): String = runCatching {
        val j = JSONObject(data)
        val tool = j.optString("tool").ifBlank { j.optString("name") }
        if (tool.isNotBlank()) "Using $tool…" else "Working…"
    }.getOrDefault("Working…")
}
