# robotOS: push-to-talk firmware for the Rabbit R1

Turns a Rabbit R1 into a single-purpose push-to-talk terminal for an AI. Hold the side button and
talk; on release, it either sends what you said or types it into the text field:

- **Keyboard closed:** speech-to-speech. Your voice streams to OpenAI's `gpt-live-1`, which answers in
  voice (about a second after you let go) and hands real thinking, plus web search, to `gpt-6.1-sol`.
  The text appears on screen too. The voice session opens as soon as the screen wakes and closes when it
  goes dark; OpenAI bills it by the second while it's open (about $0.05 a minute).
- **Keyboard open:** dictation. Your words appear in the text field as you speak
  (`gpt-live-transcribe`), dimmed until you let go, and nothing is sent, so you can edit first.
  A tap of the button sends it; `gpt-6.1-sol` answers and the reply is read aloud (`gpt-4o-mini-tts`).

The backends are ChatGPT (an OpenAI API key, the default), **Hermes Agent**, or any
other OpenAI-compatible server.

robotOS was previously called R1 PTT. Its repository is
[windoze95/robotOS](https://github.com/windoze95/robotOS). The Android package (`dev.r1ptt`),
Magisk module ID (`r1ptt_system`), and existing configuration and build filenames retain their
compatibility names.

> **Status: running on a real R1** (LineageOS 21 on Rabbit's September 2026 kernel). The side button,
> speech-to-speech voice turns and live dictation are verified on the device (final text ~0.4 s after
> release). Typed replies are built but not yet tried on it, and standby battery drain hasn't been
> measured ([docs/BATTERY.md](docs/BATTERY.md)).
>
> The current reliability changes add offline-tested transport/capture limits, interruption isolation and
> encrypted-save recovery. Its current-build hardware acceptance is pending; the observations above
> describe the earlier device-tested implementation. See [docs/RELIABILITY.md](docs/RELIABILITY.md).

## What "firmware" means here

The R1 is an Android 13 phone underneath (MediaTek MT6765). The firmware is four layers:

| Layer | What |
|---|---|
| Kernel + vendor | Rabbit's own, kept from the R1's last OTA (the side button only works with Rabbit's kernel). |
| System | LineageOS 21 (Android 14) GSI, with Rabbit's apps removed and the bloat disabled. |
| Root + tweaks | Magisk, plus the `r1ptt-system` module: button and wheel remaps, Rabbit's USB keyboard/mouse mode off, no scanning/location/Bluetooth, faster Doze. |
| App | robotOS (`dev.r1ptt`): the launcher, push-to-talk service, speech, chat and power policy. |

## Using it

| Button | Keyboard closed | Keyboard open |
|---|---|---|
| **Hold** | Talk; release to send. The reply streams and is spoken. | Dictate; release to insert at the cursor. |
| **Tap** | Screen off. When speaking, stop. (A tap on a dark screen just wakes it.) | Send the typed text. |
| **Double-tap** | New conversation. | — |
| **Scroll wheel** | Volume (always). | Volume. |

Tap the text field on the touchscreen to bring up the keyboard. The gear icon opens settings.

The message icon opens **Messages**, a one-to-one SMS handler. Type a draft, or opt in
to dictation and assistant requests such as “Tell Sam I’m on my way” or “Send a text to NUMBER.” Saved names resolve
locally; unknown or ambiguous recipients need your input. With **assistant SMS sending** enabled,
explicit completed commands send directly without review. The assistant writes a natural message
from your intent, or a short greeting when no message is supplied. Say “Text Sam exactly: MESSAGE”
for verbatim wording. Manual drafts still use **Review text**
and **Send SMS**. Sending, new incoming texts, and assistant commands each have
explicit controls in Messages → Options. No SMS permission is requested merely by opening it. Options can request Android’s default SMS
role. MMS is explicitly unsupported; incoming MMS notices are retained locally and shown as warnings.
Message details exposes native failure codes without retrying a failed or uncertain attempt.
With assistant sending enabled, voice uses completed transcription and the selected chat provider
to interpret every completed request and compose messages before sending. Unclear or unsupported requests leave a readable reply on Home
and speak it when voice replies and the network are available. Only the generic explanation is kept
in the conversation; the intercepted SMS request stays out of chat history.
Options → **Recent assistant outcomes** distinguishes missing request details from unresolved saved
recipients and retains brief result categories, including failures; it contains no message text or
recipient numbers.
MMS, RCS, old inbox import, and live carrier acceptance are not included. See [docs/MESSAGES.md](docs/MESSAGES.md).

## Battery

Idle time is where a 1000 mAh battery goes.

- **The cellular modem is off by default** (airplane mode with Wi-Fi left on). With **Use cellular
  data (SIM)** enabled, it wakes with the device and returns to airplane mode at the normal idle cut.
- **Wi-Fi switches off** after 3 minutes with the screen dark. The next button press turns it back
  on, and it reconnects while you're still talking.
- **No continuous background polling:**
  - no polling, scanning, location or Bluetooth;
  - Rabbit's apps are removed;
  - Doze starts within seconds.
  - Optional incoming SMS use Android broadcasts while the modem is reachable. Messages does not
    keep it awake; carrier-queued delivery after wake is not guaranteed.
- **The screen stays on only during a turn**, then times out after 15 s at low brightness.
- **The network is used only during a turn:** voice streams only while you hold the button, and the
  live session closes 20 s after a reply.

[docs/BATTERY.md](docs/BATTERY.md) covers the design and how to measure the idle drain.
[docs/ACCEPTANCE.md](docs/ACCEPTANCE.md) defines current-build latency, interruption, reconnect,
service-restart and overnight standby checks. Hardware and acoustic acceptance remain pending;
the read-only capture and offline analyzer include synthetic fixtures, not hardware results.

## Getting started

1. **Try the backend from your Mac first:**
   ```sh
   cp tools/r1ptt.example.json tools/r1ptt.json   # add your OpenAI API key
   tools/smoke-test.sh --play
   ```
2. **Follow [docs/INSTALL.md](docs/INSTALL.md):** unlock (Rabbit's developer mode) → stock baseline →
   LineageOS GSI → Magisk → `tools/provision.sh --config tools/r1ptt.json`.

For other backends, see [server/hermes.md](server/hermes.md), and [server/speaches.md](server/speaches.md) for
self-hosted speech.

## Repo layout

```
app/                    native Android app (Kotlin, OkHttp + coroutines; Robolectric tests)
magisk/r1ptt-system/    Magisk module: keylayout + boot-time power settings
tools/                  build, smoke-test, flash, provision, battery-report scripts
server/                 backend setup notes (Hermes, Speaches)
docs/                   install guide, battery notes
```

Build: `tools/build.sh` (JDK 17+ and the Android SDK; writes `out/r1ptt.apk` and
`out/r1ptt-system.zip`).
Tests: `./gradlew :app:testDebugUnitTest`.

App updates: **Settings → App updates** checks for signed APK releases when requested. The
release workflow is disabled until signing continuity and owner-controlled bootstrap are set up.
See [docs/UPDATES.md](docs/UPDATES.md) for release tags, secure signing setup, the first device
installation, Android confirmation and recovery. It updates the app only; hardware acceptance
of the updater remains pending.

## License

MIT; see [LICENSE](LICENSE). Parts are adapted from ClawPTT (also MIT); [NOTICE](NOTICE) has the
credits. Not affiliated with Rabbit Inc. Flashing can brick a device, and unlocking voids the R1's
warranty.
