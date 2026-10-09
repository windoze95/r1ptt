package dev.r1ptt.net

import android.os.SystemClock

/** Bounded, acknowledged GPT-Live transport. No automatic retry/replay of an utterance. */
class LiveSession(private val listener: Listener, clock: () -> Long = SystemClock::elapsedRealtime) {
    interface Listener {
        fun onEvent(event: LiveProtocol.Event)
        fun onFailure(message: String)
        fun onGone()
        fun onMetric(name: String, stats: SocketPump.Stats) {}
    }

    private val socket = SessionSocket(
        clock, ::receive, listener::onFailure, listener::onGone, listener::onMetric,
    )
    val id get() = socket.id
    val started get() = socket.ready
    val gone get() = socket.terminal

    fun connect(url: String, key: String, startMessage: String) = socket.connect(url, key, startMessage)

    fun send(message: String): Boolean = socket.send(
        message, control = message == LiveProtocol.MUTE || message == LiveProtocol.UNMUTE,
        marker = if (message == LiveProtocol.MUTE) "input_end_sent" else "",
    )

    fun close() = socket.close(if (started) LiveProtocol.CLOSE else null)
    fun reject(message: String) = socket.reject(message)

    private fun receive(text: String) {
        when (val e = LiveProtocol.parse(text)) {
            LiveProtocol.Event.Started -> if (socket.acknowledge()) listener.onEvent(e)
            is LiveProtocol.Event.Failed -> socket.reject("Voice session failed; input may be partial")
            is LiveProtocol.Event.Closed -> socket.reject("Voice session closed; input may be partial")
            else -> if (started) listener.onEvent(e)
        }
    }
}
