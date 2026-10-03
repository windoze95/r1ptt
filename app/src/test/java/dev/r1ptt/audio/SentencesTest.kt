package dev.r1ptt.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencesTest {
    private fun stream(text: String, step: Int = 3): List<String> {
        val s = SentenceSplitter()
        val out = mutableListOf<String>()
        text.chunked(step).forEach { out += s.feed(it) }
        out += s.flush()
        return out
    }

    @Test
    fun splitsAtSentenceEndsAndKeepsEverything() {
        val text = "Sure thing. The weather today is sunny with a light breeze from the west. Bring sunglasses!"
        val out = stream(text)
        assertEquals(
            listOf("Sure thing.", "The weather today is sunny with a light breeze from the west.", "Bring sunglasses!"),
            out,
        )
    }

    @Test
    fun firstPieceIsEmittedBeforeTheReplyEnds() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("Okay, here it"))
        assertEquals(listOf("Okay, here it is."), s.feed(" is. And more"))
    }

    @Test
    fun doesNotSplitInsideNumbersOrAbbreviationsWithoutSpace() {
        val out = stream("Pi is about 3.14159 in most cases. That is all there is to say about it.")
        assertEquals(listOf("Pi is about 3.14159 in most cases.", "That is all there is to say about it."), out)
    }

    @Test
    fun shortSentencesAfterTheFirstAreMerged() {
        val out = stream("First sentence here. Yes. No. Maybe so, who can really say for sure.")
        assertEquals(listOf("First sentence here.", "Yes. No. Maybe so, who can really say for sure."), out)
    }

    @Test
    fun runOnTextIsCutAtAClause() {
        val words = (1..80).joinToString(" ") { "word$it" }
        val out = stream("Intro sentence. $words, and then it just keeps going without any end in sight")
        assertTrue(out.all { it.length <= 280 })
        assertEquals("Intro sentence. $words, and then it just keeps going without any end in sight", out.joinToString(" "))
    }

    @Test
    fun cleansMarkdownForSpeech() {
        assertEquals(
            "Title Use the grep tool and see docs. Bold point",
            SpeechText.clean("# Title\nUse the `grep` tool and see [docs](https://x.y/z).\n- **Bold** point"),
        )
        assertEquals("Visit link now", SpeechText.clean("Visit https://example.com/page now"))
        assertEquals("snake_case stays", SpeechText.clean("snake_case stays"))
    }

    @Test
    fun wavHeaderIsStrippedOnlyWhenPresent() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val f = java.io.File.createTempFile("wavtest", ".wav")
        Wav.write(f, pcm, 24_000)
        val bytes = f.readBytes()
        f.delete()
        assertEquals(pcm.toList(), Wav.stripHeader(bytes).toList())
        assertEquals(pcm.toList(), Wav.stripHeader(pcm).toList())
        assertEquals(24_000, Wav.sampleRate(bytes))
        assertEquals(null, Wav.sampleRate(pcm))
    }
}
