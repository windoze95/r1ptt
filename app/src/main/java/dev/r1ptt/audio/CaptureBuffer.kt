package dev.r1ptt.audio

import java.io.ByteArrayOutputStream

/** Serializes microphone admission, bounded pre-roll and the release barrier. */
class CaptureBuffer(
    private val maxBytes: Int,
    private val preRollBytes: Int,
    private val send: (ByteArray) -> Boolean,
) {
    enum class Result { ACCEPTED, CLOSED, FULL, SEND_FAILED }
    private val recording = ByteArrayOutputStream()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var open = true
    private var failed = false
    private var streaming = false

    @Synchronized fun append(pcm: ByteArray): Result {
        if (!open) return Result.CLOSED
        if (pcm.size > maxBytes - recording.size() || (!streaming && pcm.size > preRollBytes - pendingBytes)) {
            open = false
            failed = true
            return Result.FULL
        }
        recording.write(pcm)
        if (streaming) {
            if (!send(pcm)) { open = false; failed = true; return Result.SEND_FAILED }
        } else { pending.addLast(pcm); pendingBytes += pcm.size }
        return Result.ACCEPTED
    }
    @Synchronized fun hold(): Boolean {
        if (!open) return false
        while (pending.isNotEmpty()) if (!send(pending.removeFirst())) { open = false; failed = true; return false }
        pendingBytes = 0
        streaming = true
        return true
    }
    @Synchronized fun release(commit: () -> Boolean): Boolean {
        val completeInput = open && !failed
        open = false
        streaming = false
        pending.clear()
        pendingBytes = 0
        return completeInput && commit() // all accepted appends precede this command; failed input never commits
    }
    @Synchronized fun fail(): Boolean {
        if (!open) return false // errors from an intentionally stopped AudioRecord are stale
        open = false
        failed = true
        pending.clear()
        pendingBytes = 0
        return true
    }
    @Synchronized fun failed() = failed
    @Synchronized fun stop() { open = false; streaming = false; pending.clear(); pendingBytes = 0 }
    @Synchronized fun size() = recording.size()
    @Synchronized fun bytes() = recording.toByteArray()
}
