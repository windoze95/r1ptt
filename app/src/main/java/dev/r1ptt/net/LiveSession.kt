package dev.r1ptt.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * One GPT-Live session over a WebSocket. Messages sent before the server confirms the session are
 * queued and flushed in order once it does. Callbacks arrive on OkHttp's reader thread.
 */
class LiveSession(private val listener: Listener) {
    interface Listener {
        fun onEvent(event: LiveProtocol.Event)
        /** The connection failed or dropped; [message] is short and speakable. */
        fun onFailure(message: String)
        fun onGone()
    }

    private val lock = Any()
    private val pending = ArrayList<String>()
    private var ws: WebSocket? = null
    @Volatile var started = false
        private set
    @Volatile var gone = false
        private set
    @Volatile private var closing = false

    fun connect(url: String, key: String, startMessage: String) {
        val req = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(startMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = LiveProtocol.parse(text)
                if (event == LiveProtocol.Event.Started) synchronized(lock) {
                    started = true
                    pending.forEach(webSocket::send)
                    pending.clear()
                }
                listener.onEvent(event)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = when {
                    response != null && !response.isSuccessful ->
                        friendly(ApiError(response.code, errorMessage(response.body?.string().orEmpty())))
                    else -> friendly(t)
                }
                // Hanging up after our own close request isn't a failure.
                if (!gone && !closing) listener.onFailure(message)
                finish()
            }
        })
    }

    /** Sends now if the session is up, otherwise queues until it is. Thread-safe. */
    fun send(message: String) {
        synchronized(lock) {
            if (gone) return
            if (started) ws?.send(message) else pending += message
        }
    }

    /** Asks the server to end the session (it answers session.closed, then hangs up). */
    fun close() {
        if (gone) return
        closing = true
        if (started) send(LiveProtocol.CLOSE) else ws?.cancel()
        // Don't wait forever for a polite goodbye.
        ws?.let { w -> Thread { Thread.sleep(3000); w.cancel() }.start() }
    }

    private fun finish() {
        if (gone) return
        gone = true
        synchronized(lock) { pending.clear() }
        listener.onGone()
    }

    private companion object {
        /** No read timeout (the line is quiet between turns); pings keep NAT mappings alive. */
        val client: OkHttpClient = Http.client.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
