package dev.r1ptt.net

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Real OkHttp/WebSocket terminal paths, entirely on loopback with a fixture API key. */
class SessionSocketTest {
    private class Peer(private val script: (Peer) -> Unit) : Closeable {
        private val server = ServerSocket(0)
        val url = "ws://127.0.0.1:${server.localPort}"
        val opened = CountDownLatch(1)
        private var socket: Socket? = null
        private val worker = thread(isDaemon = true, name = "fixture-ws") {
            try {
                val s = server.accept(); socket = s; s.soTimeout = 3000
                val ins = s.getInputStream(); val headers = StringBuilder()
                while (!headers.endsWith("\r\n\r\n")) {
                    val b = ins.read(); if (b < 0) return@thread
                    headers.append(b.toChar())
                }
                val key = headers.lines().first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                val digest = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
                s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${Base64.getEncoder().encodeToString(digest)}\r\n\r\n").toByteArray())
                s.getOutputStream().flush(); opened.countDown(); script(this)
            } catch (_: Exception) { } finally { socket?.close() }
        }
        fun text(message: String) {
            val data = message.toByteArray(); check(data.size < 126)
            socket!!.getOutputStream().apply { write(byteArrayOf(0x81.toByte(), data.size.toByte())); write(data); flush() }
        }
        fun hangUp() { socket?.close() }
        fun closeFrame() { socket!!.getOutputStream().apply { write(byteArrayOf(0x88.toByte(), 2, 3, 0xe8.toByte())); flush() } }
        override fun close() { socket?.close(); server.close(); worker.join(1000) }
    }
    private val clock = { System.nanoTime() / 1_000_000 }

    @Test fun peerClosingBeforeTranscriptIsFailureExactlyOnce() {
        val failed = CountDownLatch(1); val failures = AtomicInteger(); val completed = AtomicInteger()
        Peer { peer -> peer.text("""{"type":"session.updated"}"""); peer.closeFrame() }.use { peer ->
            val t = LiveTranscriber(object : LiveTranscriber.Listener {
                override fun onDelta(text: String) {}
                override fun onCompleted(text: String) { completed.incrementAndGet() }
                override fun onFailure(message: String) { failures.incrementAndGet(); failed.countDown() }
            }, clock)
            t.connect(peer.url, "fixture", "{}")
            assertTrue(failed.await(3, TimeUnit.SECONDS)); t.cancel(); t.cancel()
            assertEquals(1, failures.get()); assertEquals(0, completed.get()); assertTrue(t.done)
            assertFalse(t.send(TranscribeProtocol.COMMIT))
        }
    }
    @Test fun completedTranscriptFencesLateDeltaAndDoesNotTurnHangupIntoFailure() {
        val final = CountDownLatch(1); val failures = AtomicInteger(); val deltas = AtomicInteger()
        Peer { peer ->
            peer.text("""{"type":"session.updated"}""")
            peer.text("""{"type":"conversation.item.input_audio_transcription.completed","transcript":"fixture"}""")
            peer.text("""{"type":"conversation.item.input_audio_transcription.delta","delta":"late"}""")
        }.use { peer ->
            val t = LiveTranscriber(object : LiveTranscriber.Listener {
                override fun onDelta(text: String) { deltas.incrementAndGet() }
                override fun onCompleted(text: String) { assertEquals("fixture", text); final.countDown() }
                override fun onFailure(message: String) { failures.incrementAndGet() }
            }, clock)
            t.connect(peer.url, "fixture", "{}")
            assertTrue(final.await(3, TimeUnit.SECONDS)); assertTrue(t.done)
            assertEquals(0, failures.get()); assertEquals(0, deltas.get())
        }
    }
    @Test fun intentionalCloseIsTerminalBeforePeerCallbackAndCannotReconnectSameOwner() {
        val released = CountDownLatch(1); val failures = AtomicInteger(); val ended = AtomicInteger()
        Peer { released.await(3, TimeUnit.SECONDS) }.use { peer ->
            val s = SessionSocket(clock, {}, { failures.incrementAndGet() }, { ended.incrementAndGet() })
            s.connect(peer.url, "fixture", "{}")
            assertTrue(peer.opened.await(3, TimeUnit.SECONDS))
            s.close(); s.close(); released.countDown()
            s.connect(peer.url, "fixture", "{}")
            assertTrue(s.terminal); assertFalse(s.send("audio")); assertFalse(s.acknowledge())
            assertEquals(1, ended.get()); assertEquals(0, failures.get())
        }
    }
    @Test fun malformedUrlFailsOnceWithoutExposingInputOrLeavingOwnerAlive() {
        val failures = mutableListOf<String>(); var ends = 0
        val s = SessionSocket(clock, {}, { failures += it }, { ends++ })
        s.connect("broken-fixture-url", "fixture-secret", "{}"); s.close()
        assertTrue(s.terminal); assertEquals(1, ends); assertEquals(1, failures.size)
        assertFalse(failures.single().contains("fixture"))
    }
}
