package dev.r1ptt.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

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

/**
 * One live transcription session over a WebSocket. Audio sent before the session is configured is
 * queued and flushed in order once it is. Callbacks arrive on OkHttp's reader thread.
 */
class LiveTranscriber(private val listener: Listener) {
    interface Listener {
        fun onDelta(text: String)
        fun onCompleted(text: String)
        fun onFailure(message: String)
    }

    private val lock = Any()
    private val pending = ArrayList<String>()
    private var ws: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var done = false

    fun connect(url: String, key: String, sessionUpdate: String) {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(sessionUpdate)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val e = TranscribeProtocol.parse(text)) {
                    TranscribeProtocol.Event.Ready -> synchronized(lock) {
                        if (!ready) {
                            ready = true
                            pending.forEach(webSocket::send)
                            pending.clear()
                        }
                    }
                    is TranscribeProtocol.Event.Delta -> listener.onDelta(e.text)
                    is TranscribeProtocol.Event.Completed -> {
                        done = true
                        listener.onCompleted(e.text)
                        webSocket.close(1000, null)
                    }
                    is TranscribeProtocol.Event.Failed -> if (!done) {
                        done = true
                        listener.onFailure(e.message)
                        webSocket.cancel()
                    }
                    TranscribeProtocol.Event.Other -> {}
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (done) return
                done = true
                listener.onFailure(
                    if (response != null && !response.isSuccessful)
                        friendly(ApiError(response.code, errorMessage(response.body?.string().orEmpty())))
                    else friendly(t)
                )
            }
        })
    }

    /** Sends now if the session is configured, otherwise queues it. Thread-safe. */
    fun send(message: String) {
        synchronized(lock) {
            if (done) return
            if (ready) ws?.send(message) else pending += message
        }
    }

    /** Drops the session without waiting for a transcript (the press was a tap). */
    fun cancel() {
        done = true
        ws?.cancel()
    }

    private companion object {
        val client: OkHttpClient = Http.client.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
