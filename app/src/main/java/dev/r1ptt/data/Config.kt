package dev.r1ptt.data

import java.net.URI

/** How a backend remembers the conversation between turns. */
enum class SessionMode(val id: String) {
    /** Stateless server: the app sends the last N messages every turn (OpenAI and most servers). */
    HISTORY("history"),

    /** OpenClaw: only the new message is sent; the gateway keys its agent session off the `user` field. */
    OPENCLAW_USER("openclaw-user"),

    /** Hermes Agent: only the new message is sent; `X-Hermes-Session-Id` continues the server-side transcript. */
    HERMES_SESSION("hermes-session");

    companion object {
        fun of(id: String?): SessionMode = entries.firstOrNull { it.id == id } ?: HISTORY
    }
}

/** A chat backend that speaks OpenAI-style `/chat/completions`. */
data class Provider(
    val id: String,
    val label: String,
    val baseUrl: String,
    val apiKey: String = "",
    val model: String,
    val session: SessionMode = SessionMode.HISTORY,
    /** Extra request headers, e.g. Cloudflare Access service-token headers. */
    val headers: Map<String, String> = emptyMap(),
    /** A JSON object merged into every chat request body (e.g. reasoning_effort, verbosity). */
    val extraBody: String = "{}",
    /** Read timeout for the streamed reply; agents can work for minutes. */
    val timeoutSec: Int = 120,
)

/** An OpenAI-compatible audio endpoint (`/audio/transcriptions` or `/audio/speech`). */
data class Endpoint(
    val baseUrl: String,
    /** Blank means "reuse the key of a provider on the same host". */
    val apiKey: String = "",
    val model: String,
)

data class Tts(
    val enabled: Boolean = true,
    val endpoint: Endpoint = Endpoint(Config.OPENAI_URL, model = "gpt-4o-mini-tts-2025-12-15"),
    val voice: String = "marin",
    /** "pcm" (raw 16-bit, OpenAI) or "wav" (servers without raw PCM, e.g. Speaches). */
    val format: String = "pcm",
    /** Sample rate of raw PCM replies (OpenAI and Kokoro: 24 kHz); WAV replies carry their own. */
    val sampleRate: Int = 24_000,
    /** Optional speaking-style hint; only sent when set (gpt-4o-mini-tts understands it). */
    val instructions: String = "",
)

/**
 * Speech-to-speech for voice turns (keyboard closed) on the OpenAI provider: gpt-live-1 hears the
 * user and answers in voice, handing anything that needs real thinking to [backendModel]. Typed and
 * dictated turns keep using chat + text-to-speech.
 */
data class Live(
    val enabled: Boolean = true,
    val url: String = "wss://api.openai.com/v1/live/sessions",
    val model: String = "gpt-live-1",
    val voice: String = "marin",
    val backendModel: String = "gpt-6.1-sol",
    val reasoningEffort: String = "low",
    val webSearch: Boolean = true,
    /** The session is billed per second while open; close it this long after the last reply. */
    val idleCloseSec: Int = 20,
)

data class Power(
    /** Turn Wi-Fi off after this many minutes with the screen off; 0 keeps it on. */
    val wifiIdleMinutes: Int = 3,
    val screenTimeoutSec: Int = 15,
    /** Manual backlight level, 1..255. */
    val brightness: Int = 60,
    /** Keep the cellular modem on (needs a SIM). Off by default: an idle modem is the biggest drain. */
    val cellular: Boolean = false,
)

data class Config(
    val activeProvider: String = "openai",
    val providers: Map<String, Provider> = Presets.providers,
    val systemPrompt: String = DEFAULT_STYLE,
    /** Messages of context sent each turn in [SessionMode.HISTORY] mode. */
    val historyMessages: Int = 12,
    val stt: Endpoint = Endpoint(OPENAI_URL, model = "gpt-transcribe"),
    /** ISO-639-1 language hint for transcription; blank = auto-detect. */
    val sttLanguage: String = "",
    val tts: Tts = Tts(),
    val live: Live = Live(),
    val power: Power = Power(),
    /** Short beeps when listening starts and on errors. */
    val earcons: Boolean = true,
    val textSizeSp: Int = 17,
    /** Keep each recorded clip under Android/data/dev.r1ptt/files/clips, for tuning the mic. */
    val saveClips: Boolean = false,
    /** evdev name of the side button's input device. */
    val buttonDevice: String = "mtk-kpd",
) {
    val provider: Provider get() = providers[activeProvider] ?: providers.values.first()

    /** Voice turns go speech-to-speech only on OpenAI; agent backends need their own pipeline. */
    val liveVoice: Boolean get() = live.enabled && provider.id == "openai" && keyFor(liveEndpoint).isNotBlank()

    val liveEndpoint: Endpoint get() = Endpoint(live.url, model = live.model)

    /** The key for [endpoint]: its own if set, else the key of a provider on the same host. */
    fun keyFor(endpoint: Endpoint): String {
        if (endpoint.apiKey.isNotBlank()) return endpoint.apiKey
        val host = hostOf(endpoint.baseUrl) ?: return ""
        return (listOf(provider) + providers.values)
            .firstOrNull { hostOf(it.baseUrl) == host && it.apiKey.isNotBlank() }
            ?.apiKey.orEmpty()
    }

    companion object {
        const val OPENAI_URL = "https://api.openai.com/v1"

        const val DEFAULT_STYLE =
            "You are answering through a tiny push-to-talk device with a 2.9-inch screen, and your " +
                "replies are also read aloud. Be brief: one to three short sentences unless asked for " +
                "more. Plain text only: no markdown, lists, tables, code blocks or emoji."

        fun hostOf(url: String): String? = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull()
    }
}

object Presets {
    val providers: Map<String, Provider> = listOf(
        Provider(
            id = "openai", label = "ChatGPT (OpenAI)", baseUrl = Config.OPENAI_URL, model = "gpt-6.1-sol",
            extraBody = """{"reasoning_effort":"low","verbosity":"low"}""",
        ),
        Provider(
            id = "openclaw", label = "OpenClaw", baseUrl = "http://openclaw.local:18789/v1",
            model = "openclaw/default", session = SessionMode.OPENCLAW_USER, timeoutSec = 300,
        ),
        Provider(
            id = "hermes", label = "Hermes Agent", baseUrl = "http://hermes.local:8642/v1",
            model = "hermes-agent", session = SessionMode.HERMES_SESSION, timeoutSec = 300,
        ),
        Provider(id = "custom", label = "Custom", baseUrl = "http://localhost:8080/v1", model = "default"),
    ).associateBy { it.id }
}
