package dev.r1ptt.net

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Messages of OpenAI's Realtime transcription session (gpt-live-transcribe): stream 24 kHz PCM while
 * the button is held, commit on release, get word-by-word deltas and then the final transcript
 * (about half a second after the commit in testing). Pure, so it is unit-tested.
 */
object TranscribeProtocol {
    const val SAMPLE_RATE = 24_000
    const val COMMIT = """{"type":"input_audio_buffer.commit"}"""

    /** Manual turns (turn_detection null): the push-to-talk release is the end of the utterance. */
    fun sessionUpdate(model: String, delay: String, languages: List<String>): String {
        val transcription = JSONObject().put("model", model).put("delay", delay)
        if (languages.isNotEmpty()) transcription.put("languages", JSONArray(languages))
        val input = JSONObject()
            .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
            .put("transcription", transcription)
            .put("turn_detection", JSONObject.NULL)
        val session = JSONObject().put("type", "transcription").put("audio", JSONObject().put("input", input))
        return JSONObject().put("type", "session.update").put("session", session).toString()
    }

    fun append(pcm: ByteArray): String =
        """{"type":"input_audio_buffer.append","audio":"${Base64.getEncoder().encodeToString(pcm)}"}"""

    sealed interface Event {
        data object Ready : Event
        data class Delta(val text: String) : Event
        data class Completed(val text: String) : Event
        data class Failed(val message: String) : Event
        data object Other : Event
    }

    fun parse(text: String): Event {
        val j = runCatching { JSONObject(text) }.getOrNull() ?: return Event.Other
        return when (j.optString("type")) {
            "session.updated" -> Event.Ready
            "conversation.item.input_audio_transcription.delta" -> Event.Delta(j.optString("delta"))
            "conversation.item.input_audio_transcription.completed" -> Event.Completed(j.optString("transcript"))
            "conversation.item.input_audio_transcription.failed" ->
                Event.Failed(j.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Transcription failed" })
            "error" -> Event.Failed(j.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Transcription error" })
            else -> Event.Other
        }
    }
}

/** Bounded one-shot dictation transport; premature closure is a terminal failure. */
class LiveTranscriber(private val listener: Listener, clock: () -> Long = android.os.SystemClock::elapsedRealtime) {
    interface Listener {
        fun onDelta(text: String)
        fun onCompleted(text: String)
        fun onFailure(message: String)
        fun onMetric(name: String, stats: SocketPump.Stats) {}
    }
    private val socket = SessionSocket(
        clock, ::receive, listener::onFailure,
        metric = listener::onMetric,
    )
    val id get() = socket.id
    val ready get() = socket.ready
    val done get() = socket.terminal

    fun connect(url: String, key: String, sessionUpdate: String) = socket.connect(url, key, sessionUpdate)
    fun send(message: String): Boolean = socket.send(
        message, control = message == TranscribeProtocol.COMMIT,
        marker = if (message == TranscribeProtocol.COMMIT) "input_end_sent" else "",
    )
    fun cancel() = socket.close()

    private fun receive(text: String) {
        when (val e = TranscribeProtocol.parse(text)) {
            TranscribeProtocol.Event.Ready -> socket.acknowledge()
            is TranscribeProtocol.Event.Delta -> if (ready) listener.onDelta(e.text)
            is TranscribeProtocol.Event.Completed -> if (ready) {
                socket.close() // fence late deltas before publishing the final result
                listener.onCompleted(e.text)
            }
            is TranscribeProtocol.Event.Failed -> socket.reject("Transcription failed; input may be partial")
            TranscribeProtocol.Event.Other -> {}
        }
    }
}
