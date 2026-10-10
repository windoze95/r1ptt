package dev.r1ptt.messages

import dev.r1ptt.data.Config
import dev.r1ptt.net.CallRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SmsIntentClientTest {
    private lateinit var server: MockWebServer
    private val request = "Could you send Sam a message saying Meet at six."
    private val answer = """{"kind":"sms","recipient":"Sam","body":"Meet at six."}"""
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun close() { server.shutdown() }
    private fun cfg(provider: String = "openai") = Config().let { c -> c.copy(activeProvider = provider,
        providers = c.providers + (provider to c.providers.getValue(provider).copy(baseUrl = server.url("/v1").toString(), apiKey = "synthetic-key"))) }
    private fun chunk(text: String? = null, finish: String? = null) = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject()
        .put("delta", JSONObject().apply { text?.let { put("content", it) } })
        .put("finish_reason", finish ?: JSONObject.NULL))).toString() + "\n\n"
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
    private fun stream(text: String = answer) = chunk(text.take(20)) + chunk(text.drop(20)) + chunk(finish = "stop") + "data: [DONE]\n\n"

    @Test fun allChatProvidersUseAStatelessCompleteStructuredResponse() {
        for (provider in listOf("openai", "hermes", "custom")) {
            server.enqueue(response(stream()).throttleBody(19, 1, TimeUnit.MILLISECONDS))
            assertEquals(SmsIntent.Send(SmsComposeAction("Sam", "Meet at six.")), SmsIntentClient().resolve(cfg(provider), request, CallRegistry()))
            val http = server.takeRequest()
            assertEquals("/v1/chat/completions", http.path)
            val messages = JSONObject(http.body.readUtf8()).getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals(request, messages.getJSONObject(1).getString("content"))
            assertEquals("Bearer synthetic-key", http.getHeader("Authorization"))
            if (provider == "hermes") assertTrue(http.getHeader("X-Hermes-Session-Id")!!.startsWith("r1ptt-intent-"))
            else assertNull(http.getHeader("X-Hermes-Session-Id"))
        }
    }

    @Test fun parserCannotInheritHistoryOrProviderToolAndPromptOverrides() {
        val base = cfg("hermes")
        val c = base.copy(systemPrompt = "private old chat", providers = base.providers + ("hermes" to base.provider.copy(
            headers = mapOf("x-hermes-session-id" to "old-conversation", "Test-Header" to "keep"),
            extraBody = """{"messages":[],"tools":[{}],"stream":false,"model":"override","temperature":0}""")))
        val a = SmsIntentClient.build(c, request); val b = SmsIntentClient.build(c, request)
        assertEquals(base.provider.model, a.body.getString("model"))
        assertTrue(a.body.getBoolean("stream")); assertFalse(a.body.has("tools"))
        assertEquals(0, a.body.getInt("temperature")); assertEquals("keep", a.headers["Test-Header"])
        assertFalse(a.body.toString().contains("private old chat"))
        assertNotEquals(a.headers["X-Hermes-Session-Id"], b.headers["X-Hermes-Session-Id"])
        assertFalse(a.headers.containsKey("x-hermes-session-id"))
    }

    @Test fun jsonFallbackRequiresAnExplicitStop() {
        for (finish in listOf("stop", "length", "tool_calls")) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", answer)).put("finish_reason", finish))).toString()))
            if (finish == "stop") assertTrue(SmsIntentClient().resolve(cfg(), request, CallRegistry()) is SmsIntent.Send)
            else assertThrows(Exception::class.java) { SmsIntentClient().resolve(cfg(), request, CallRegistry()) }
        }
    }

    @Test fun truncatedDuplicatedOrPostCompletionStreamsNeverReturnAnAction() {
        for (sse in listOf(chunk(answer), chunk(answer) + "data: [DONE]\n\n",
            chunk(answer) + chunk(finish = "length") + "data: [DONE]\n\n",
            chunk(answer) + chunk(finish = "stop") + chunk(finish = "stop") + "data: [DONE]\n\n",
            chunk(answer) + chunk(finish = "stop") + chunk(" ") + "data: [DONE]\n\n",
            stream(answer + answer), stream("x".repeat(4097)), stream("I will send it."))) {
            server.enqueue(response(sse))
            assertThrows(Exception::class.java) { SmsIntentClient().resolve(cfg(), request, CallRegistry()) }
        }
    }

    @Test fun providerErrorAndCancelledHttpCannotProduceAnAction() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("synthetic private provider error"))
        assertThrows(Exception::class.java) { SmsIntentClient().resolve(cfg(), request, CallRegistry()) }
        server.takeRequest()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val calls = CallRegistry(); val pool = Executors.newSingleThreadExecutor()
        try {
            val pending = pool.submit<SmsIntent> { SmsIntentClient().resolve(cfg(), request, calls) }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            calls.cancelAll()
            assertThrows(Exception::class.java) { pending.get(5, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
    }
}
