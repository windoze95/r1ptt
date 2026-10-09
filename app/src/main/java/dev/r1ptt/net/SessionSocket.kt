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
    // The pump and socket share one monitor: a rejected send can terminate reentrantly without
    // taking a second lock in the reverse order of the watchdog/connect paths.
    private val pump = SocketPump(clock, ::fail, metric)
    private val lock get() = pump
    private var socket: WebSocket? = null
    private var watch: ScheduledFuture<*>? = null
    private var connected = false
    @Volatile var terminal = false
        private set
    val ready: Boolean get() = pump.ready

    fun connect(url: String, key: String, handshake: String) {
        synchronized(lock) {
            if (terminal || connected) return
            connected = true
            try {
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key").build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    synchronized(lock) {
                    if (terminal) { ws.cancel(); return }
                    metric("socket_open", pump.stats())
                    pump.handshake(handshake)
                    }
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    synchronized(lock) {
                        if (!terminal) {
                            if (text.length > 1024 * 1024) fail("Voice event too large") else event(text)
                        }
                    }
                }
                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    if (!terminal) fail("Voice connection closed; input may be partial")
                }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    if (!terminal) fail("Voice connection closed; input may be partial")
                }
                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    // Never log URL, response bodies, transcripts, or raw provider errors here.
                    if (!terminal) fail(if (response?.code in listOf(401, 403)) "Bad API key or no access" else "Voice connection failed; input may be partial")
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
    }

    fun acknowledge(): Boolean {
        val wasReady = ready
        val ok = pump.acknowledge()
        if (ok && !wasReady) metric("connect_ready", pump.stats())
        scheduleWatch()
        return ok
    }

    fun send(text: String, control: Boolean = false, marker: String = ""): Boolean {
        val ok = pump.offer(text, control, marker)
        scheduleWatch()
        return ok
    }

    fun reject(message: String) = fail(message)

    /** Invalidate callbacks before touching the wire; closure never waits on a server goodbye. */
    fun close(goodbye: String? = null) {
        if (!terminate()) return
        val ws = synchronized(lock) { socket.also { socket = null } }
        if (goodbye != null && ws != null && ws.send(goodbye) && ws.close(1000, null)) {
            timer.schedule({ ws.cancel() }, 3, TimeUnit.SECONDS)
        } else ws?.cancel()
        ended()
    }

    private fun fail(message: String) {
        if (!terminate()) return
        synchronized(lock) { socket?.cancel(); socket = null }
        failed(message)
        ended()
    }

    private fun terminate(): Boolean = synchronized(lock) {
        if (terminal) return false
        terminal = true
        watch?.cancel(false)
        watch = null
        pump.close()
        true
    }

    private fun scheduleWatch(): Unit = synchronized(lock) {
        if (terminal || watch != null || !pump.needsWatch()) return@synchronized
        watch = timer.schedule({
            synchronized(lock) { watch = null }
            pump.poll()
            scheduleWatch()
        }, 50, TimeUnit.MILLISECONDS)
    }

    private companion object {
        val ids = AtomicLong()
        val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "voice-socket-watch").apply { isDaemon = true } }
        val client = Http.client.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
    }
}
