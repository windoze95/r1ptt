package dev.r1ptt

import dev.r1ptt.net.LiveProtocol
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveTest {
    @Test
    fun sessionStartMatchesTheGptLiveSchema() {
        val j = JSONObject(LiveProtocol.start("gpt-live-1", "Be brief.", "marin", "gpt-6.1-sol", "low", webSearch = true))
        assertEquals("session.start", j.getString("type"))
        val s = j.getJSONObject("session")
        assertEquals("gpt-live-1", s.getString("model"))
        assertEquals("Be brief.", s.getString("instructions"))
        assertEquals("marin", s.getJSONObject("audio").getJSONObject("output").getString("voice"))
        val d = s.getJSONObject("delegation")
        assertEquals("responses", d.getString("type"))
        val r = d.getJSONObject("responses")
        assertEquals("gpt-6.1-sol", r.getString("model"))
        assertEquals("low", r.getJSONObject("reasoning").getString("effort"))
        assertEquals("web_search", r.getJSONArray("tools").getJSONObject(0).getString("type"))
        val noSearch = JSONObject(LiveProtocol.start("m", "i", "v", "b", "low", webSearch = false))
        assertFalse(noSearch.getJSONObject("session").getJSONObject("delegation").getJSONObject("responses").has("tools"))
    }

    @Test
    fun appendCarriesBase64Pcm() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val j = JSONObject(LiveProtocol.append(pcm))
        assertEquals("session.input_audio.append", j.getString("type"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(j.getString("audio")))
    }

    @Test
    fun parsesServerEvents() {
        assertEquals(LiveProtocol.Event.Started, LiveProtocol.parse("""{"type":"session.started","session":{}}"""))
        val audio = LiveProtocol.parse("""{"type":"session.output_audio.delta","delta":"AQIDBA=="}""") as LiveProtocol.Event.Audio
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.pcm)
        assertEquals(LiveProtocol.Event.Heard("What is"), LiveProtocol.parse("""{"type":"session.input_transcript.delta","delta":"What is","start_ms":600}"""))
        assertEquals(LiveProtocol.Event.Said(" Everest"), LiveProtocol.parse("""{"type":"session.output_transcript.delta","delta":" Everest"}"""))
        assertEquals(LiveProtocol.Event.DelegationStarted, LiveProtocol.parse("""{"type":"session.delegation.created","delegation":{"id":"x"}}"""))
        val search = LiveProtocol.parse("""{"type":"response.event","event":{"type":"response.web_search_call.searching"}}""") as LiveProtocol.Event.Backend
        assertTrue(search.searching)
        assertFalse(search.finished)
        val done = LiveProtocol.parse("""{"type":"response.event","event":{"type":"response.completed"}}""") as LiveProtocol.Event.Backend
        assertTrue(done.finished)
        assertEquals(LiveProtocol.Event.Closed("expired"), LiveProtocol.parse("""{"type":"session.closed","reason":"expired"}"""))
        assertEquals(LiveProtocol.Event.Failed("bad audio"), LiveProtocol.parse("""{"type":"error","error":{"message":"bad audio"}}"""))
        assertEquals(LiveProtocol.Event.Other, LiveProtocol.parse("""{"type":"session.usage.updated"}"""))
        assertEquals(LiveProtocol.Event.Other, LiveProtocol.parse("not json"))
    }

    private var now = 0L
    private fun tracker() = LiveTracker(clock = { now }, quietMs = 1200, noReplyMs = 20_000)

    @Test
    fun aSimpleExchange() {
        val t = tracker()
        assertEquals(LiveTracker.Phase.IDLE, t.phase(false))
        t.hold()
        t.audio(speech = true) // the model talking over a hold is held back, not a reply
        assertEquals(LiveTracker.Phase.LISTENING, t.phase(false))
        now = 3000; t.release()
        assertEquals(LiveTracker.Phase.WAITING, t.phase(false))
        now = 3700; t.audio(speech = true)
        assertEquals(LiveTracker.Phase.SPEAKING, t.phase(true))
        now = 6000; t.audio(speech = true)
        now = 6500
        assertEquals(LiveTracker.Phase.SPEAKING, t.phase(false)) // brief gap between words
        now = 7300
        assertEquals(LiveTracker.Phase.IDLE, t.phase(false))
        assertFalse(t.noReply())
    }

    @Test
    fun aPauseWhileTheThinkingModelWorksIsNotTheEnd() {
        val t = tracker()
        t.hold(); now = 2000; t.release()
        now = 2600; t.audio(speech = true) // "Let me check."
        t.delegationStarted()
        t.backend(finished = false, isSearch = true)
        now = 9000 // seven quiet seconds of web search
        assertEquals(LiveTracker.Phase.LOOKING_UP, t.phase(false))
        assertTrue(t.lookingUpWeb)
        t.backend(finished = true, isSearch = false)
        now = 9500; t.audio(speech = true)
        assertEquals(LiveTracker.Phase.SPEAKING, t.phase(true))
        now = 11_000
        assertEquals(LiveTracker.Phase.IDLE, t.phase(false))
    }

    @Test
    fun silenceIsNotAReplyAndEventuallyTimesOut() {
        val t = tracker()
        t.hold(); now = 1000; t.release()
        now = 2000; t.audio(speech = false)
        assertEquals(LiveTracker.Phase.WAITING, t.phase(false))
        now = 21_000
        assertTrue(t.noReply())
        assertEquals(LiveTracker.Phase.IDLE, t.phase(false))
    }
}
