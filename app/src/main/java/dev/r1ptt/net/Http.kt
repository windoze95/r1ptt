package dev.r1ptt.net

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

object Http {
    val JSON = "application/json".toMediaType()

    /**
     * One client for everything, so transcription, chat and speech requests to the same host share
     * a single pooled TLS connection: fewer handshakes, less radio time.
     */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun withReadTimeout(seconds: Int): OkHttpClient =
        client.newBuilder().readTimeout(seconds.toLong(), TimeUnit.SECONDS).build()

    /** Opens (and pools) a connection to [baseUrl] while the user is still talking. */
    fun warm(baseUrl: String) {
        runCatching {
            client.newCall(Request.Builder().url(baseUrl).head().build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) = response.close()
            })
        }
    }

    fun Request.Builder.bearer(key: String): Request.Builder =
        apply { if (key.isNotBlank()) header("Authorization", "Bearer $key") }
}

/** A non-2xx answer from a server, with the server's own message when it gave one. */
class ApiError(val code: Int, message: String) : IOException(message.ifBlank { "HTTP $code" })

/** Collects in-flight calls so a cancelled turn can abort them from another thread. */
class CallRegistry {
    private val calls = CopyOnWriteArrayList<Call>()
    @Volatile private var cancelled = false

    fun add(call: Call) {
        calls += call
        if (cancelled) call.cancel()
    }

    fun cancelAll() {
        cancelled = true
        calls.forEach { it.cancel() }
    }
}

/** Pulls `error.message` (OpenAI style) or `detail`/`message` out of an error body. */
fun errorMessage(body: String): String = runCatching {
    val j = JSONObject(body)
    j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
        ?: j.optString("error").takeIf { it.isNotBlank() }
        ?: j.optString("detail").takeIf { it.isNotBlank() }
        ?: j.optString("message")
}.getOrNull().orEmpty().ifBlank { body.take(200) }

/** Short, speakable explanations for the failures a user can do something about. */
fun friendly(e: Throwable): String = when (e) {
    is ApiError -> when (e.code) {
        401, 403 -> "Bad API key or no access"
        404 -> "Not found: check the URL and model"
        429 -> "Rate limited or out of credit"
        in 500..599 -> "Server error (${e.code})"
        else -> e.message ?: "HTTP ${e.code}"
    }
    is UnknownHostException -> "Can't find the server"
    is ConnectException -> "Can't connect to the server"
    is SocketTimeoutException -> "Timed out"
    is SSLException -> "Secure connection failed"
    else -> e.message ?: e.javaClass.simpleName
}
