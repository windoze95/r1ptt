package dev.r1ptt.net

import org.junit.Assert.assertEquals
import org.junit.Test

class SseTest {
    private fun parse(vararg lines: String): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        val p = SseParser { e, d -> out += e to d }
        lines.forEach(p::line)
        p.finish()
        return out
    }

    @Test
    fun standardEvents() {
        val out = parse("data: {\"a\":1}", "", "data: {\"a\":2}", "", "data: [DONE]", "")
        assertEquals(listOf(null to "{\"a\":1}", null to "{\"a\":2}", null to "[DONE]"), out)
    }

    @Test
    fun commentsKeepalivesAndCrlf() {
        val out = parse(": keepalive", "data: {\"a\":1}\r", "\r", ":", "data: [DONE]")
        assertEquals(listOf(null to "{\"a\":1}", null to "[DONE]"), out)
    }

    @Test
    fun namedEvents() {
        val out = parse("event: hermes.tool.progress", "data: {\"tool\":\"web\"}", "", "data: {\"x\":1}", "")
        assertEquals(listOf("hermes.tool.progress" to "{\"tool\":\"web\"}", null to "{\"x\":1}"), out)
    }

    @Test
    fun missingBlankLinesBetweenJsonEvents() {
        val out = parse("data: {\"a\":1}", "data: {\"a\":2}", "data: [DONE]")
        assertEquals(listOf(null to "{\"a\":1}", null to "{\"a\":2}", null to "[DONE]"), out)
    }

    @Test
    fun multiLineDataIsJoined() {
        val out = parse("data: {\"a\":", "data: 1}", "")
        assertEquals(listOf(null to "{\"a\":\n1}"), out)
    }

    @Test
    fun chunkParsing() {
        assertEquals(ChatChunk.Text("Hi"), ChatChunk.parse("""{"choices":[{"delta":{"content":"Hi"}}]}"""))
        assertEquals(ChatChunk.Other, ChatChunk.parse("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals(ChatChunk.Other, ChatChunk.parse("""{"choices":[{"delta":{"content":null}}]}"""))
        assertEquals(ChatChunk.Other, ChatChunk.parse("""{"choices":[]}"""))
        assertEquals(ChatChunk.Done, ChatChunk.parse("[DONE]"))
        assertEquals(ChatChunk.Failed("Overloaded"), ChatChunk.parse("""{"error":{"message":"Overloaded"}}"""))
        assertEquals(ChatChunk.Text("Whole"), ChatChunk.parse("""{"choices":[{"message":{"content":"Whole"}}]}"""))
        assertEquals("Hi there", ChatChunk.fullMessage("""{"choices":[{"message":{"role":"assistant","content":"Hi there"}}]}"""))
    }

    @Test
    fun errorMessages() {
        assertEquals("Incorrect API key", errorMessage("""{"error":{"message":"Incorrect API key","type":"x"}}"""))
        assertEquals("nope", errorMessage("""{"detail":"nope"}"""))
        assertEquals("plain text", errorMessage("plain text"))
    }

    @Test
    fun transcriptParsing() {
        assertEquals("hello world", SttClient.parse("""{"text":" hello world "}"""))
        assertEquals("hello", SttClient.parse("hello\n"))
        assertEquals("", SttClient.parse("""{"other":1}"""))
    }
}
