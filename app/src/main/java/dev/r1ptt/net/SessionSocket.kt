package dev.r1ptt.net

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One owner for socket admission, acknowledgement, deadlines and terminal callbacks. */
class SessionSocket(
    clock: () -> Long,
    private val event: (String) -> Unit,
    private val failed: (String) -> Unit,
    private val ended: () -> Unit = {},
    private val metric: (String, SocketPump.Stats) -> Unit = { _, _ -> },
) {
    val id = ids.incrementAndGet()
    private val pump = SocketPump(clock, ::fail, metric)
    private val lock get() = pump
    private var socket: WebSocket? = null
    private var watch: ScheduledFuture<*>? = null
    private var connected = false
    private data class Notice(val message: String?)
    private data class Termination(val socket: WebSocket?)
    private var notice: Notice? = null
    @Volatile var terminal = false
        private set
    val ready: Boolean get() = pump.ready

    fun connect(url: String, key: String, handshake: String): Unit = locked {
        if (terminal || connected) return@locked
        connected = true
        try {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response): Unit = locked {
                    if (terminal) { ws.cancel(); return@locked }
                    metric("socket_open", pump.stats())
                    pump.handshake(handshake)
                }
                override fun onMessage(ws: WebSocket, text: String): Unit = locked {
                    if (!terminal) {
                        if (text.length > 1024 * 1024) fail("Voice event too large") else event(text)
                    }
                }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) = fail("Voice connection closed; input may be partial")
                override fun onClosed(ws: WebSocket, code: Int, reason: String) = fail("Voice connection closed; input may be partial")
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    // Never log URL, response bodies, transcripts, or raw provider errors here.
                    fail(if (response?.code in listOf(401, 403)) "Bad API key or no access" else "Voice connection failed; input may be partial")
                }
            })
            val ws = socket!!
            pump.bind(object : SocketPump.Wire {
                override fun send(text: String) = ws.send(text)
                override fun queuedBytes() = ws.queueSize()
            })
            scheduleWatch()
        } catch (_: Exception) {
            fail("Voice connection could not start")
        }
    }

    fun acknowledge(): Boolean = locked {
        val wasReady = ready
        val ok = pump.acknowledge()
        if (ok && !wasReady) metric("connect_ready", pump.stats())
        scheduleWatch()
        ok
    }

    fun send(text: String, control: Boolean = false, marker: String = ""): Boolean = locked {
        val ok = pump.offer(text, control, marker)
        scheduleWatch()
        ok
    }

    fun reject(message: String) = fail(message)

    /** Invalidate callbacks before touching the wire; closure never waits on a server goodbye. */
    fun close(goodbye: String? = null): Unit = locked {
        val stop = terminate(null) ?: return@locked
        val ws = stop.socket
        if (goodbye != null && ws != null && ws.send(goodbye) && ws.close(1000, null)) {
            timer.schedule({ ws.cancel() }, 3, TimeUnit.SECONDS)
        } else ws?.cancel()
    }

    private fun fail(message: String): Unit = locked {
        val stop = terminate(message) ?: return@locked
        stop.socket?.cancel()
    }

    /** Caller owns the pump monitor. Observers run only after the outermost owner releases it. */
    private fun terminate(message: String?): Termination? {
        if (terminal) return null
        terminal = true
        watch?.cancel(false)
        watch = null
        pump.close()
        notice = Notice(message)
        return Termination(socket.also { socket = null })
    }

    private fun <T> locked(action: () -> T): T = try {
        synchronized(lock, action)
    } finally {
        notifyTerminal()
    }

    private fun notifyTerminal() {
        // A pump rejection / protocol event can nest locked(). Do not call a capture owner while
        // still holding the socket monitor: capture sends acquire these monitors in reverse order.
        if (Thread.holdsLock(lock)) return
        val next = synchronized(lock) { notice.also { notice = null } } ?: return
        try { next.message?.let(failed) } finally { ended() }
    }

    private fun scheduleWatch() {
        if (terminal || watch != null || !pump.needsWatch()) return
        watch = timer.schedule({
            locked {
                watch = null
                pump.poll()
                scheduleWatch()
            }
        }, 50, TimeUnit.MILLISECONDS)
    }

    private companion object {
        val ids = AtomicLong()
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-socket-watch").apply { isDaemon = true } }
        val client = Http.client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
}
