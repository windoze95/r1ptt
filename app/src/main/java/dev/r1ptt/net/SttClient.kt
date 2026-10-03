package dev.r1ptt.net

import dev.r1ptt.audio.Clip
import dev.r1ptt.data.Config
import dev.r1ptt.net.Http.bearer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

/** Speech-to-text through any OpenAI-compatible `/audio/transcriptions` endpoint. */
class SttClient {
    fun transcribe(cfg: Config, clip: Clip, calls: CallRegistry): String {
        val ep = cfg.stt
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", ep.model)
            .addFormDataPart("response_format", "json")
            .apply { if (cfg.sttLanguage.isNotBlank()) addFormDataPart("language", cfg.sttLanguage) }
            .addFormDataPart("file", clip.file.name, clip.file.asRequestBody(clip.mime.toMediaType()))
            .build()
        val req = Request.Builder()
            .url(ep.baseUrl.trimEnd('/') + "/audio/transcriptions")
            .bearer(cfg.keyFor(ep))
            .post(form)
            .build()
        val call = Http.client.newCall(req)
        calls.add(call)
        call.execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiError(resp.code, errorMessage(body))
            return parse(body)
        }
    }

    companion object {
        /** `{"text": "..."}` from OpenAI-style servers; some local servers answer with plain text. */
        fun parse(body: String): String {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) return trimmed
            return runCatching { JSONObject(trimmed).optString("text") }.getOrDefault("").trim()
        }
    }
}
