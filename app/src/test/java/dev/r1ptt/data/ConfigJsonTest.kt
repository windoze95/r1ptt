package dev.r1ptt.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("gpt-6.1-sol", c.providers.getValue("openai").model)
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
            JSONObject("""{"activeProvider":"hermes","providers":{"openai":{"apiKey":"sk-o"},"hermes":{"apiKey":"tok"}}}"""),
        )
        assertEquals("sk-o", c.keyFor(c.stt)) // STT is on api.openai.com even though Hermes is active
        assertEquals("own", c.keyFor(c.stt.copy(apiKey = "own")))
        assertEquals("", c.keyFor(Endpoint("http://speaches.lan:8000/v1", model = "x")))
    }

    @Test
    fun retiredProfilesRemainStoredButCannotBeSelectedOrSupplyKeys() {
        assertFalse(Config().providers.containsKey("openclaw"))
        val c = ConfigJson.merge(Config(), JSONObject("""{
            "activeProvider":"openclaw",
            "providers":{"openclaw":{"baseUrl":"http://retired.example/v1","apiKey":"retained-test-token",
                "model":"retired","session":"openclaw-user","headers":{"X-Private":"retained-test-header"}}},
            "power":{"wifiIdleMinutes":0,"brightness":42}
        }"""))
        assertFalse(c.availableProviders.containsKey("openclaw"))
        assertEquals("openai", c.activeProvider)
        assertEquals("openai", c.provider.id)
        assertEquals("", c.keyFor(Endpoint("http://retired.example/v1", model = "speech")))
        assertEquals(0, c.power.wifiIdleMinutes)
        assertEquals(42, c.power.brightness)
        val back = ConfigJson.merge(Config(), ConfigJson.toJson(c))
        assertEquals("retained-test-token", back.providers.getValue("openclaw").apiKey)
        assertEquals(mapOf("X-Private" to "retained-test-header"), back.providers.getValue("openclaw").headers)
        assertFalse(back.availableProviders.containsKey("openclaw"))
    }

    @Test
    fun rejectedRetiredSelectionKeepsAnExistingHermesSelectionAndCustomProfile() {
        val base = ConfigJson.merge(Config(), JSONObject("""{
            "activeProvider":"hermes",
            "providers":{"hermes":{"apiKey":"hermes-test-key"},
                "lan":{"baseUrl":"http://lan.example/v1","model":"custom","apiKey":"lan-test-key"}}
        }"""))
        val c = ConfigJson.merge(base, JSONObject("""{"activeProvider":"openclaw"}"""))
        assertEquals("hermes", c.activeProvider)
        assertEquals(base.provider, c.provider)
        assertEquals(base.providers.getValue("lan"), c.availableProviders.getValue("lan"))
    }
}
