package dev.r1ptt.net

import dev.r1ptt.data.Config
import dev.r1ptt.net.Http.bearer
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Text-to-speech through any OpenAI-compatible `/audio/speech` endpoint, as raw 16-bit PCM (or WAV
 * for servers without raw output) so it can be played while it downloads, with no decoder.
 */
class TtsClient(private val config: () -> Config) {
    fun call(text: String): Call {
        val cfg = config()
        val t = cfg.tts
        val body = JSONObject()
            .put("model", t.endpoint.model)
            .put("input", text)
            .put("voice", t.voice)
            .put("response_format", if (t.format == "wav") "wav" else "pcm")
        if (t.instructions.isNotBlank()) body.put("instructions", t.instructions)
        val req = Request.Builder()
            .url(t.endpoint.baseUrl.trimEnd('/') + "/audio/speech")
            .bearer(cfg.keyFor(t.endpoint))
            .post(body.toString().toRequestBody(Http.JSON))
            .build()
        return Http.client.newCall(req)
    }
}
