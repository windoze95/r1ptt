package dev.r1ptt.input

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.r1ptt.sys.Root
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the side button straight from its evdev node through a root shell.
 *
 * Why not just onKeyDown: the R1's side button is the power key (input device mtk-kpd, scancode
 * 116). The keylayout module remaps it to BUTTON_1 with WAKE, but Android's window policy still
 * swallows the press that wakes the screen, so a hold that starts with the screen off would never
 * reach an app. The kernel node sees every press in every screen state. (Approach from ClawPTT.)
 *
 * `dd bs=24` copies one raw `struct input_event` per read with no buffering. `getevent` would
 * block-buffer its output when writing to a pipe and deliver presses kilobytes late.
 * The shell prints READY only after confirming the node exists, so [alive] flips only once root
 * was actually granted. While [alive] is false, the launcher's own key handling drives the button.
 */
class SideButton(
    private val deviceName: () -> String,
    /** Called on the main thread with true on press and false on release. */
    private val onEdge: (down: Boolean) -> Unit,
) {
    @Volatile var alive = false
        private set

    @Volatile private var running = false
    @Volatile private var proc: Process? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "side-button").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        alive = false
        proc?.destroy()
        proc = null
        thread?.interrupt()
        thread = null
    }

    /** Keeps a reader session alive, backing off (1 s up to 30 s) while root is unavailable. */
    private fun loop() {
        var backoff = 1000L
        while (running) {
            val wasUp = session()
            if (!running) break
            if (wasUp) backoff = 1000L
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
            }
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    /** One root session; true if the stream came up. */
    private fun session(): Boolean {
        val node = findNode() ?: return false
        val p = Root.stream("test -c $node && echo READY && exec dd if=$node bs=${InputEvent.SIZE} 2>/dev/null")
            ?: return false
        proc = p
        var up = false
        var down = false
        try {
            val ins = BufferedInputStream(p.inputStream)
            if (readLine(ins) != "READY") return false
            up = true
            alive = true
            Log.i(TAG, "side button reader up on $node")
            val buf = ByteArray(InputEvent.SIZE)
            while (running) {
                if (!readFully(ins, buf)) break
                val e = InputEvent.parse(buf)
                if (e.type != InputEvent.EV_KEY || e.code != InputEvent.KEY_POWER) continue
                when (e.value) {
                    1 -> if (!down) { down = true; main.post { onEdge(true) } }
                    0 -> if (down) { down = false; main.post { onEdge(false) } }
                    // 2 = auto-repeat while held
                }
            }
        } catch (_: IOException) {
        } finally {
            alive = false
            if (down) main.post { onEdge(false) } // never leave a hold dangling
            runCatching { p.destroy() }
            if (proc === p) proc = null
        }
        return up
    }

    /** The button's /dev/input node, looked up by device name since event numbers can shuffle. */
    private fun findNode(): String? {
        val name = deviceName()
        val devices = Root.read("cat /proc/bus/input/devices") ?: return null
        val node = InputEvent.findHandler(devices, name)
        if (node == null) Log.w(TAG, "input device \"$name\" not found; using the framework key path only")
        return node
    }

    private fun readLine(ins: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 64) {
            val c = ins.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trim()
            sb.append(c.toChar())
        }
        return null
    }

    private fun readFully(ins: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private companion object {
        const val TAG = "r1ptt"
    }
}

/** A decoded 64-bit `struct input_event`: a 16-byte timeval, then u16 type, u16 code, s32 value. */
data class InputEvent(val type: Int, val code: Int, val value: Int) {
    companion object {
        const val SIZE = 24
        const val EV_KEY = 1
        const val KEY_POWER = 116

        fun parse(b: ByteArray): InputEvent {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return InputEvent(bb.getShort(16).toInt() and 0xffff, bb.getShort(18).toInt() and 0xffff, bb.getInt(20))
        }

        /** Finds `/dev/input/eventN` for the device called [name] in /proc/bus/input/devices text. */
        fun findHandler(devices: String, name: String): String? {
            val block = devices.split(Regex("\\n\\s*\\n")).firstOrNull { it.contains("N: Name=\"$name\"") } ?: return null
            val handlers = block.lines().firstOrNull { it.startsWith("H: Handlers=") } ?: return null
            return Regex("\\bevent\\d+\\b").find(handlers)?.value?.let { "/dev/input/$it" }
        }
    }
}
