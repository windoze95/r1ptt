# Hermes SMS relay

> **Retired.** robotOS now uses Hermes as the agent directly: see [HERMES.md](HERMES.md). The relay's
> services are stopped and disabled on the Hermes host; this page describes the old design.

The relay is optional and starts disabled. **Messages → Options → SMS relay** and
**Settings → SMS relay** open its controls. Until enabled, existing PTT SMS commands
continue through the current assistant. The relay uses the same SIM, native SMS
executor, callback handling, and Messages history.

## Behavior

- With relay and assistant SMS sending enabled, an unlocked owner's completed PTT
  command creates one durable request. A clear immediate send needs no second
  review. Recipient-only requests permit a neutral greeting; exact-wording commands
  retain the existing literal-text validation.
- Draft requests produce an unsent item in Messages. Ambiguous requests never
  grant send authority. Unknown names must be resolved locally; the contact book is
  not uploaded.
- Incoming SMS stays local. **Ask Hermes about this message** shares only the
  selected message from a saved, unblocked US recipient. Security-code patterns
  and service senders are rejected before upload. Selecting the same incoming
  record again does not create another model request, including after restart or
  content expiry. Pattern detection is conservative and cannot identify every
  sensitive message; there is no blanket inbox forwarding.
- Selecting a message authorizes an explanation only. There is no automatic reply,
  inbox command execution, model-callable send tool, MMS, or RCS implementation.
- Cancel commits a local dispatch barrier first, then requests server cancellation.
  A native handoff that already happened keeps its truthful carrier callbacks.
  Missing callbacks mean unknown, and never trigger an automatic resend.

## Power and VPN

Tailscale is the primary connection. Standalone WireGuard is a manually selected
fallback. Android allows one active VPN at a time: stop Tailscale, enable the
WireGuard tunnel, and select **Use WireGuard fallback**; reverse those steps to
return. Switching paths retains request IDs and enrollment, so it cannot create a
second native attempt.

If Tailscale's **Sign in** button does nothing on a minimal GSI, check whether an
HTTPS browser handler exists. Tailscale logs an authorization URL when no browser
can open it. Open that device-specific URL on another computer, sign into the same
tailnet, and approve the R1. Accept Android's VPN connection prompt on the R1.

Docked mode is a separate opt-in. With external power present (including a full
battery), it keeps radios available and runs a `remoteMessaging` foreground
service with a private notification and Pause action. It opens no microphone or
live voice session. It pauses for deliberate airplane mode, battery saver, battery
below 15%, or Android thermal status MODERATE or worse.

On unplug, the radio exception ends and the existing idle policy resumes (three
minutes by default). There are no scheduled relay wakeups in screen-off pocket
mode. A bounded sync pass holds a wake lock for at most 30 seconds. Pending work
backs off to one minute; an empty docked queue checks in every 15 minutes. The app
caps estimated metered bridge traffic at 1 MiB per UTC day. This includes an
overhead allowance, not a measurement of all VPN or device traffic.

## Host isolation and authority

`server/bridge` uses Python's standard library and SQLite. Device tokens are
random, hashed on the host, and limited to that device's commands. Administration
and device enrollment are local CLI operations; no remote administration route is
exposed. The R1 stores its token in the existing encrypted configuration. The
Hermes API key stays on the host.

The bridge targets Hermes revision
`38880bd2f1e90dbc9a1aeec03af62539ee64719a`. The separate `r1-messaging` profile has
no enabled tools, MCP servers, private skills, memories, SOUL context, or background
review. The adapter runs in a separate process without the gateway scheduler.
Runtime guards reject a changed Hermes revision, tools, a provider change, or a
fallback chain. Hermes' supported root credential-pool fallback supplies the
existing `openai-codex` login; OAuth refresh tokens are not copied into the profile.

Every request has a fresh server-owned session. A selected correspondent message
cannot choose an owner session, model, toolset, endpoint, or history. A fixed
nonempty conversation history prevents implicit loading of previous transcripts.
Only completed Runs API output is considered; streamed fragments are never sent.

The host and phone both persist IDs before network work. The host freezes the
recipient, complete body, SIM, segment count and native attempt ID. Dispatch needs
a fresh, short-lived server grant. The phone also requires a monotonic lease and
atomically consumes authority in the same SQLite transaction that records the
native attempt. A crash between that transaction and Android handoff is uncertain
and is not retried. A revoked device cannot obtain new grants; an already issued
grant can remain usable for its short lifetime.

Limits are five minutes of request validity, 20 pending requests, 50 model requests
per device/day, three SMS parts per send, ten sends to a recipient/day, and fifty
outbound parts per device/day. Admission counts remain consumed after cancellation.
Model execution is limited to one concurrent run, one iteration, and a 60-second
Hermes run budget. The pinned Codex transport does not enforce `max_tokens`, so
these controls are **not a verified dollar cap or hard output-token cap**. A paid
provider or billing-route change requires separate budget acceptance.

The reviewed US fixed/mobile destination pattern is pinned in
`server/bridge/us_nanp.json`; it excludes other NANP countries, territories,
toll-free and premium ranges. The bridge additionally rejects 976 exchanges.
Unknown new numbering ranges fail closed. After reviewing a metadata update, run
`python3 tools/sync-bridge-numbering.py` to regenerate Android's matcher. Upstream
attribution and its Apache license are in `third_party/libphonenumber`.

## Retention

Relay payloads, results and frozen copies are cleared after 24 hours when cleanup
runs. Android cleanup runs when sync resumes; an offline phone can retain them
longer. Host cleanup deletes the dedicated Hermes session and transcript through
its session API. A separate idle maintenance pass expires Hermes' terminal replay
results 24 hours after their last update, within about one minute while running.
Failed cleanup stays pending and retries. IDs and keyed fingerprints remain as
replay tombstones. Native Messages history, Android's SMS provider, backups, and
the model/speech providers have separate retention. SQLite is private app/host
storage, not application-encrypted message storage or a forensic secure erase.
Bridge services discard content logs; never attach raw configurations or database
files to issues.

## macOS deployment

Use a clean checkout of this repository on the Hermes host, Homebrew Python 3,
Tailscale, and Caddy. Preserve the existing default Hermes profile. Create the
empty named profile, then provision the two private LaunchAgents:

```sh
hermes profile create r1-messaging --no-alias --no-skills
python3 -m server.bridge.deploy_macos \
  --hermes "$HOME/.hermes/hermes-agent" --model gpt-6-astra --start
```

State lives in `~/Library/Application Support/robotOS/bridge` (0700), with secrets
and LaunchAgent files at 0600. Hermes listens only on `127.0.0.1:8643`; the bridge
listens only on `127.0.0.1:8650`. Check authenticated `/v1/capabilities` and
`/v1/toolsets` locally before enrolling a device; all tools must be disabled.

Run an enrolled userspace `tailscaled` with a private state directory and socket at
`~/Library/Application Support/robotOS/tailscale/tailscaled.sock`. Enable HTTPS
and Serve for the tailnet. Use the host's actual tailnet DNS name below. The
WireGuard peer must already have a working tunnel to the LAN; route only the
bridge host's `/32` in its client configuration.

```sh
python3 -m server.bridge.network_macos \
  --hostname HOST.TAILNET.ts.net \
  --lan-address 192.168.20.10 --wireguard-peer 192.168.2.7
```

This configures private Tailscale Serve on 443 and Caddy on the specified LAN IP's
8443. Caddy accepts only the selected WireGuard peer IP and proxies the bridge,
with no admin endpoint or access log. It uses a normal certificate for the same
tailnet DNS name. Daily certificate renewal preserves the previous certificate if
Tailscale is unavailable; the fallback can operate without Tailscale until that
certificate expires. It does not create public port forwarding or a new WireGuard
server. Client AllowedIPs constrain routing, not the router's access policy.

Create one private enrollment file per device:

```sh
python3 -m server.bridge --db "$HOME/Library/Application Support/robotOS/bridge/bridge.db" enroll \
  --url https://HOST.TAILNET.ts.net \
  --wireguard-url https://HOST.TAILNET.ts.net:8443 \
  --wireguard-address 192.168.20.10 \
  --output /private/path/r1.enrollment.json
```

Import that partial JSON through robotOS's existing configuration receiver. It
merges only bridge settings and starts disabled. The optional fallback IPv4
override changes DNS resolution only; certificate and hostname verification
remain enabled. Keep enrollment files and WireGuard keys private, and remove any
temporary shared-storage import copy after device import.

To revoke a lost device, run `python3 -m server.bridge --db PATH revoke DEVICE_UUID`.
To stop the host services, use `launchctl bootout gui/$(id -u)/LABEL` for
`ai.robotos.r1-bridge`, `ai.robotos.hermes-messaging`, `ai.robotos.bridge-https`,
and `ai.robotos.bridge-https-renew`. Disable relay on the phone to restore the
existing PTT path. Do not restore an older APK over the upgraded message database
without a compatible migration or the pre-upgrade database backup.

Local acceptance builds may use `-PreleaseTag=v0.3.6 -PreleaseSigning=true
-PlocalBuildSuffix=bridge-dev` with the already-installed signing identity. This
keeps version code 3006 and visibly labels the unpublished build; a later official
release remains an upgrade. It does not create a Git tag or publish a release.

## Verification and outstanding acceptance

Run the checks used by CI:

```sh
python3 -m unittest discover -s server/bridge/tests -v
node --test tools/acceptance.test.mjs tools/release/*.test.mjs
./gradlew testDebugUnitTest assembleDebug assembleRelease lintDebug
```

Automated coverage includes ten database restarts/replays, one native handoff,
changed frozen fields, uncertain create recovery, cancellation/revocation,
cross-device access, draft-only authority, selected-message isolation, quotas,
US destinations, OTP rejection, database migration, and simulated power cycles.
These are not carrier or battery acceptance results.

Before calling the deployment accepted, record both VPN paths from the R1 with
normal TLS verification; actual reboot, process-death, Doze and airplane-mode
behavior; ten physical cable cycles; an eight-hour powered screen-off soak;
unplugged baseline versus enabled-but-undocked battery drain; and native carrier
send/receive tests to explicitly agreed recipients and content. Record native
send and delivery callbacks separately from the recipient's report. Do not send
real SMS merely to exercise this checklist. Carrier entitlement/permitted use,
an exact monetary model budget, automatic replies and MMS remain separate decisions.
