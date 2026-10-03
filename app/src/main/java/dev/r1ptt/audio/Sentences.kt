package dev.r1ptt.audio

/**
 * Cuts a streaming reply into speakable pieces: at sentence ends once a piece is long enough
 * (shorter for the first, so speech starts sooner), and at a comma or space when a sentence runs
 * long, since speech latency grows with input length.
 */
class SentenceSplitter(
    private val firstMin: Int = 8,
    private val nextMin: Int = 40,
    private val maxLen: Int = 280,
) {
    private val buf = StringBuilder()
    private var count = 0

    fun feed(delta: String): List<String> {
        buf.append(delta)
        return take()
    }

    /** Whatever is left once the reply is complete. */
    fun flush(): List<String> {
        val out = take().toMutableList()
        emit(buf.length, out)
        return out
    }

    private fun take(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val end = boundary(if (count == 0) firstMin else nextMin)
                ?: (if (buf.length > maxLen) softBoundary() else null)
                ?: break
            emit(end, out)
        }
        return out
    }

    /** Index just past the first sentence end at or beyond [min] chars that is followed by whitespace. */
    private fun boundary(min: Int): Int? {
        for (i in buf.indices) {
            if (i + 1 < min) continue
            when (buf[i]) {
                '\n' -> return i + 1
                '.', '!', '?', '…' -> if (i + 1 < buf.length && buf[i + 1].isWhitespace()) return i + 1
            }
        }
        return null
    }

    private fun softBoundary(): Int {
        val window = buf.substring(0, maxLen)
        val clause = window.lastIndexOfAny(charArrayOf(',', ';', ':'))
        if (clause > maxLen / 2) return clause + 1
        val space = window.lastIndexOf(' ')
        return if (space > 0) space + 1 else maxLen
    }

    private fun emit(end: Int, out: MutableList<String>) {
        val s = buf.substring(0, end).trim()
        buf.delete(0, end)
        if (s.isNotEmpty()) {
            out += s
            count++
        }
    }
}

/** Makes model output sound right when read aloud (the style prompt asks for plain text, but still). */
object SpeechText {
    private val codeBlock = Regex("```[\\s\\S]*?```")
    private val inlineCode = Regex("`([^`]*)`")
    private val link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val url = Regex("https?://\\S+")
    private val lineMarker = Regex("(?m)^\\s{0,3}(#{1,6}|[-*+•]|\\d+[.)])\\s+")
    private val emphasis = Regex("(\\*{1,3}|_{2,3}|~~)")
    private val space = Regex("\\s+")

    fun clean(s: String): String = s
        .replace(codeBlock, " ")
        .replace(inlineCode, "$1")
        .replace(link, "$1")
        .replace(url, "link")
        .replace(lineMarker, "")
        .replace(emphasis, "")
        .replace(space, " ")
        .trim()
}
