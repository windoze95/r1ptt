package dev.r1ptt.net

import dev.r1ptt.data.Config
import dev.r1ptt.data.ConfigJson
import dev.r1ptt.data.Msg
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestTest {
    private val conversation = listOf(
        Msg(Msg.USER, "my name is Sam"),
        Msg(Msg.ASSISTANT, "Hi Sam."),
        Msg(Msg.USER, "what's my name?"),
    )

    private fun cfg(active: String, key: String = "k") = Config().let { c ->
        c.copy(activeProvider = active, providers = c.providers + (active to c.providers.getValue(active).copy(apiKey = key)))
    }

    @Test
    fun openAiSendsHistoryAndExtras() {
        val b = ChatRequest.build(cfg("openai", "sk-test"), conversation, "abc")
        assertEquals("https://api.openai.com/v1/chat/completions", b.url)
        assertEquals("Bearer sk-test", b.headers["Authorization"])
        assertNull(b.headers["X-Hermes-Session-Id"])
        val body = b.body
        assertEquals("gpt-6.1-sol", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertFalse(body.has("user"))
        assertEquals("low", body.getString("reasoning_effort"))
        val msgs = body.getJSONArray("messages")
        assertEquals(4, msgs.length()) // system + 3
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("what's my name?", msgs.getJSONObject(3).getString("content"))
    }

    @Test
    fun historyIsCapped() {
        val c = cfg("openai").copy(historyMessages = 2, systemPrompt = "")
        val msgs = ChatRequest.build(c, conversation, "abc").body.getJSONArray("messages")
        assertEquals(2, msgs.length())
        assertEquals("Hi Sam.", msgs.getJSONObject(0).getString("content"))
    }

    @Test
    fun retiredConfigurationCannotConstructAGatewayRequest() {
        val c = ConfigJson.merge(Config(), JSONObject("""{
            "activeProvider":"openclaw","providers":{"openclaw":{
                "baseUrl":"http://retired.example/v1","apiKey":"unused-test-token",
                "model":"retired","session":"openclaw-user"}}
        }"""))
        val b = ChatRequest.build(c, conversation, "abc")
        assertEquals("https://api.openai.com/v1/chat/completions", b.url)
        assertNull(b.headers["Authorization"])
        assertFalse(b.body.has("user"))
        assertEquals(4, b.body.getJSONArray("messages").length())
    }

    @Test
    fun hermesContinuesTheServerSession() {
        val b = ChatRequest.build(cfg("hermes"), conversation, "abc")
        assertEquals("r1ptt-abc", b.headers["X-Hermes-Session-Id"])
        assertFalse(b.body.has("user"))
        assertEquals(2, b.body.getJSONArray("messages").length())
    }

    @Test
    fun noKeyMeansNoAuthHeaderAndExtraHeadersPassThrough() {
        val base = cfg("custom", key = "")
        val c = base.copy(
            providers = base.providers + ("custom" to base.provider.copy(
                baseUrl = "https://x.example/v1/", headers = mapOf("CF-Access-Client-Id" to "id"),
            ))
        )
        val b = ChatRequest.build(c, conversation, "abc")
        assertEquals("https://x.example/v1/chat/completions", b.url)
        assertNull(b.headers["Authorization"])
        assertEquals("id", b.headers["CF-Access-Client-Id"])
    }
}
