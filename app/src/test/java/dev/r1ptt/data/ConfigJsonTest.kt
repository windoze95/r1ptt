package dev.r1ptt.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigJsonTest {
    private fun normalized(c: Config) = c.copy(providers = c.providers.mapValues { it.value.copy(extraBody = "{}") })

    @Test
    fun roundTripsTheDefaults() {
        val back = ConfigJson.merge(Config(), ConfigJson.toJson(Config()))
        assertEquals(normalized(Config()), normalized(back))
        val extraBefore = JSONObject(Config().providers.getValue("openai").extraBody)
        val extraAfter = JSONObject(back.providers.getValue("openai").extraBody)
        assertTrue(extraBefore.similar(extraAfter))
    }

    @Test
    fun partialImportOnlyTouchesWhatItNames() {
        val c = ConfigJson.merge(Config(), JSONObject("""{"providers":{"openai":{"apiKey":"sk-1"}},"power":{"wifiIdleMinutes":0}}"""))
        assertEquals("sk-1", c.providers.getValue("openai").apiKey)
        assertEquals("gpt-5-mini", c.providers.getValue("openai").model)
        assertEquals(Config().providers.getValue("hermes"), c.providers.getValue("hermes"))
        assertEquals(0, c.power.wifiIdleMinutes)
        assertEquals(Config().power.screenTimeoutSec, c.power.screenTimeoutSec)
    }

    @Test
    fun importAcceptsExtraBodyAsObjectAndNewProviders() {
        val c = ConfigJson.merge(
            Config(),
            JSONObject(
                """{"activeProvider":"lan","providers":{"lan":{"baseUrl":"http://10.0.0.2:8080/v1","model":"llama",
                   "session":"history","extraBody":{"temperature":0.2},"headers":{"X-Test":"1"}}}}"""
            ),
        )
        val p = c.provider
        assertEquals("lan", p.id)
        assertEquals("llama", p.model)
        assertEquals(0.2, JSONObject(p.extraBody).getDouble("temperature"), 0.0)
        assertEquals(mapOf("X-Test" to "1"), p.headers)
    }

    @Test(expected = Exception::class)
    fun invalidExtraBodyStringIsRejected() {
        ConfigJson.merge(Config(), JSONObject("""{"providers":{"openai":{"extraBody":"{not json"}}}"""))
    }

    @Test
    fun audioEndpointsBorrowTheKeyOfAProviderOnTheSameHost() {
        val c = ConfigJson.merge(
            Config(),
            JSONObject("""{"activeProvider":"openclaw","providers":{"openai":{"apiKey":"sk-o"},"openclaw":{"apiKey":"tok"}}}"""),
        )
        assertEquals("sk-o", c.keyFor(c.stt)) // STT is on api.openai.com even though OpenClaw is active
        assertEquals("own", c.keyFor(c.stt.copy(apiKey = "own")))
        assertEquals("", c.keyFor(Endpoint("http://speaches.lan:8000/v1", model = "x")))
    }
}
