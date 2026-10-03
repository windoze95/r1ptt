package dev.r1ptt.net

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Messages of OpenAI's GPT-Live WebSocket protocol (`/v1/live/sessions`): a full-duplex voice
 * model that hears raw 24 kHz PCM, answers in the same format, and delegates real thinking to a
 * Responses model. Pure (no Android), so it is unit-tested.
 */
object LiveProtocol {
    const val SAMPLE_RATE = 24_000

    const val MUTE = """{"type":"session.input_audio.mute"}"""
    const val UNMUTE = """{"type":"session.input_audio.unmute"}"""
    const val CLOSE = """{"type":"session.close"}"""

    fun start(
        model: String,
        instructions: String,
        voice: String,
        backendModel: String,
        reasoningEffort: String,
        webSearch: Boolean,
    ): String {
        val responses = JSONObject()
            .put("model", backendModel)
            .put("reasoning", JSONObject().put("effort", reasoningEffort))
            .put("text", JSONObject().put("verbosity", "low"))
        if (webSearch) responses.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        val session = JSONObject()
            .put("model", model)
            .put("instructions", instructions)
            .put("audio", JSONObject().put("output", JSONObject().put("voice", voice)))
            .put("delegation", JSONObject().put("type", "responses").put("responses", responses))
        return JSONObject().put("type", "session.start").put("session", session).toString()
    }

    fun append(pcm: ByteArray): String =
        """{"type":"session.input_audio.append","audio":"${Base64.getEncoder().encodeToString(pcm)}"}"""

    sealed interface Event {
        data object Started : Event
        class Audio(val pcm: ByteArray) : Event
        data class Heard(val delta: String) : Event
        data class Said(val delta: String) : Event
        data object DelegationStarted : Event
        /** A nested Responses event from the thinking model, e.g. `response.web_search_call.searching`. */
        data class Backend(val type: String) : Event {
            val finished: Boolean get() = type in FINISHED
            val searching: Boolean get() = type.startsWith("response.web_search_call")
        }
        data class Closed(val reason: String) : Event
        data class Failed(val message: String) : Event
        data object Other : Event
    }

    private val FINISHED = setOf("response.completed", "response.failed", "response.incomplete", "response.cancelled")

    fun parse(text: String): Event {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return Event.Other
        return when (j.optString("type")) {
            "session.started" -> Event.Started
            "session.output_audio.delta" ->
                runCatching { Event.Audio(Base64.getDecoder().decode(j.optString("delta"))) }.getOrDefault(Event.Other)
            "session.input_transcript.delta" -> Event.Heard(j.optString("delta"))
            "session.output_transcript.delta" -> Event.Said(j.optString("delta"))
            "session.delegation.created" -> Event.DelegationStarted
            "response.event" -> Event.Backend(j.optJSONObject("event")?.optString("type").orEmpty())
            "session.closed" -> Event.Closed(j.optString("reason"))
            "error" -> Event.Failed(j.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Live session error" })
            else -> Event.Other
        }
    }
}
