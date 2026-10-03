package dev.r1ptt.audio

import android.media.AudioManager
import android.media.ToneGenerator
import kotlin.concurrent.thread

/** Tiny audible cues for when you're not looking at the screen. */
object Earcon {
    fun listening() = play(ToneGenerator.TONE_PROP_BEEP, 60)
    fun error() = play(ToneGenerator.TONE_PROP_NACK, 300)

    private fun play(tone: Int, ms: Int) {
        thread(name = "earcon") {
            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
                tg.startTone(tone, ms)
                Thread.sleep(ms + 60L)
                tg.release()
            }
        }
    }
}
