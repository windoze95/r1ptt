# r1ptt: push-to-talk firmware for the Rabbit R1

Turns a Rabbit R1 into a single-purpose push-to-talk terminal for an AI. Hold the side button and
talk; on release, it either sends what you said or types it into the text field:

- **Keyboard closed:** speech-to-speech. Your voice streams to OpenAI's `gpt-live-1`, which answers in
  voice (about a second after you let go) and hands real thinking, plus web search, to `gpt-6.1-sol`.
  The text appears on screen too. The voice session opens as soon as the screen wakes and closes when it
  goes dark; OpenAI bills it by the second while it's open (about $0.05 a minute).
- **Keyboard open:** your words are transcribed (`gpt-transcribe`) into the text field and not sent,
  so you can edit first. A tap of the button sends it; `gpt-6.1-sol` answers and the reply is read
  aloud (`gpt-4o-mini-tts`).

The backends are ChatGPT (an OpenAI API key, the default), **OpenClaw**, **Hermes Agent**, or any
other OpenAI-compatible server.

> **Status: running on a real R1** (LineageOS 21 on Rabbit's September 2026 kernel). The side button
> and speech-to-speech voice turns are verified on the device. Dictation and typed replies are built
> but not yet tried on it, and standby battery drain hasn't been measured ([docs/BATTERY.md](docs/BATTERY.md)).

## What "firmware" means here

The R1 is an Android 13 phone underneath (MediaTek MT6765). The firmware is four layers:

| Layer | What |
|---|---|
| Kernel + vendor | Rabbit's own, kept from the R1's last OTA (the side button only works with Rabbit's kernel). |
| System | LineageOS 21 (Android 14) GSI, with Rabbit's apps removed and the bloat disabled. |
| Root + tweaks | Magisk, plus the `r1ptt-system` module: button and wheel remaps, Rabbit's USB keyboard/mouse mode off, no scanning/location/Bluetooth, faster Doze. |
| App | `dev.r1ptt`: the launcher, push-to-talk service, speech, chat and power policy. |

## Using it

| Button | Keyboard closed | Keyboard open |
|---|---|---|
| **Hold** | Talk; release to send. The reply streams and is spoken. | Dictate; release to insert at the cursor. |
| **Tap** | Screen off. When speaking, stop. (A tap on a dark screen just wakes it.) | Send the typed text. |
| **Double-tap** | New conversation. | — |
| **Scroll wheel** | Volume (always). | Volume. |

Tap the text field on the touchscreen to bring up the keyboard. The gear icon opens settings.

## Battery

Idle time is where a 1000 mAh battery goes.

- **The cellular modem is always off** (airplane mode with Wi-Fi left on). A SIM is optional.
- **Wi-Fi switches off** after 3 minutes with the screen dark. The next button press turns it back
  on, and it reconnects while you're still talking.
- **Nothing runs in the background:**
  - no polling, scanning, location or Bluetooth;
  - Rabbit's apps are removed;
  - Doze starts within seconds.
- **The screen stays on only during a turn**, then times out after 15 s at low brightness.
- **The network is used only during a turn:** voice streams only while you hold the button, and the
  live session closes 20 s after a reply.

[docs/BATTERY.md](docs/BATTERY.md) covers the design and how to measure the idle drain.

## Getting started

1. **Try the backend from your Mac first:**
   ```sh
   cp tools/r1ptt.example.json tools/r1ptt.json   # add your OpenAI API key
   tools/smoke-test.sh --play
   ```
2. **Follow [docs/INSTALL.md](docs/INSTALL.md):** unlock (Rabbit's developer mode) → stock baseline →
   LineageOS GSI → Magisk → `tools/provision.sh --config tools/r1ptt.json`.

For other backends, see [server/openclaw.md](server/openclaw.md),
[server/hermes.md](server/hermes.md), and [server/speaches.md](server/speaches.md) for
self-hosted speech.

## Repo layout

```
app/                    the Android app (Kotlin, no AndroidX; OkHttp + coroutines)
magisk/r1ptt-system/    Magisk module: keylayout + boot-time power settings
tools/                  build, smoke-test, flash, provision, battery-report scripts
server/                 backend setup notes (OpenClaw, Hermes, Speaches)
docs/                   install guide, battery notes
```

Build: `tools/build.sh` (JDK 17+ and the Android SDK; writes `out/r1ptt.apk` and
`out/r1ptt-system.zip`).
Tests: `./gradlew :app:testDebugUnitTest`.

## License

MIT; see [LICENSE](LICENSE). Parts are adapted from ClawPTT (also MIT); [NOTICE](NOTICE) has the
credits. Not affiliated with Rabbit Inc. Flashing can brick a device, and unlocking voids the R1's
warranty.
