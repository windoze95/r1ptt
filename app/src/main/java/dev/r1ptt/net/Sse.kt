package dev.r1ptt.net

import org.json.JSONObject

/** Minimal Server-Sent Events parser: feed it lines, get (event, data) pairs. */
class SseParser(private val onEvent: (event: String?, data: String) -> Unit) {
    private var event: String? = null
    private val data = StringBuilder()

    fun line(raw: String) {
        val line = raw.removeSuffix("\r")
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) return // comment / keepalive
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                // Some servers skip the blank line between events. A complete JSON object already
                // buffered means this line starts a new event.
                if (data.isNotEmpty() && isComplete(data)) dispatch()
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
    }

    fun finish() = dispatch()

    private fun dispatch() {
        if (data.isNotEmpty()) onEvent(event, data.toString())
        event = null
        data.setLength(0)
    }

    private fun isComplete(sb: StringBuilder): Boolean {
        val s = sb.trim()
        return s == "[DONE]" || (s.startsWith("{") && s.endsWith("}") && runCatching { JSONObject(s.toString()) }.isSuccess)
    }
}

/** What one chat-completions stream event carried. */
sealed interface ChatChunk {
    data class Text(val delta: String) : ChatChunk
    data class Failed(val message: String) : ChatChunk
    data object Done : ChatChunk
    data object Other : ChatChunk

    companion object {
        fun parse(data: String): ChatChunk {
            if (data.trim() == "[DONE]") return Done
            val j = runCatching { JSONObject(data) }.getOrNull() ?: return Other
            j.optJSONObject("error")?.let { return Failed(it.optString("message").ifBlank { "Server error" }) }
            val choice = j.optJSONArray("choices")?.optJSONObject(0) ?: return Other
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return Other
            val content = delta.opt("content")
            return if (content is String && content.isNotEmpty()) Text(content) else Other
        }

        /** The whole reply from a non-streamed chat-completions response. */
        fun fullMessage(body: String): String =
            JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }
}
