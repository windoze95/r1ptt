# Hermes remote

With the **Hermes Agent** provider selected, robotOS is a remote for your Hermes agent. Hermes is
the agent; the R1 only relays and acts:

- **Push-to-talk:** hold the button and talk. robotOS transcribes the speech (OpenAI live
  transcription), sends the text to Hermes unchanged, and speaks Hermes's reply. The R1 does no SMS
  interpretation of its own in this mode.
- **Hermes acts on the R1:** Hermes's `robotos` MCP server gives it `send_sms`, `sms_status`,
  `recent_texts`, `list_recipients` and `r1_status`. "Text Sam I'm running late" is Hermes calling
  `mcp_robotos_send_sms`, which the R1 carries out with its own SIM and number.
- **Texting Hermes:** texts from your own number to the R1 go to Hermes (session `robotos-sms`), and
  Hermes's reply is texted back. Texts from anyone else stay in Messages.
- **Plugged in = radios stay up.** On external power the Wi-Fi/airplane idle cut is skipped, so texts
  arrive and Hermes can reach the R1 at any time. On battery the normal three-minute cut applies, and
  the R1 is reachable only while awake. Power never switches the passthrough on or off.

```
 PTT ──► R1: transcribe ──► https://HOST.TAILNET.ts.net/v1/chat/completions ──► Hermes
                                   (Tailscale Serve → 127.0.0.1:8642)                       │
 R1 ◄── device API :8765 ◄── tailscaled HTTP proxy 127.0.0.1:1056 ◄── robotos MCP (stdio) ◄─┘
```

## Pieces

| Where | What |
|---|---|
| R1 | `HermesLink` config (`hermes.*`): device API on port 8765, token, passthrough owner. |
| R1 | `DeviceApi`: JSON over HTTP; serves only Tailscale (100.64.0.0/10, fd7a:115c:a1e0::/48) and loopback peers presenting the token. |
| R1 | `HermesTexts`: owner texts → Hermes → reply text; retried with backoff for 30 minutes, then one notice. Hermes is asked once per text. |
| Hermes host | Default profile gateway (`hermes gateway install`) with the API server on 127.0.0.1:8642. |
| Hermes host | `server/robotos_mcp`: stdio MCP server (standard library only) that calls the R1 through the userspace tailscaled's outbound HTTP proxy. |
| Hermes host | `server/hermes/skills/robotos/SKILL.md`: when and how Hermes uses the R1. |

Device API endpoints (all need `Authorization: Bearer <token>`): `GET /v1/status`, `POST /v1/sms`
`{"to","body"}`, `GET /v1/sms/<id>`, `GET /v1/texts[?with=&limit=]`, `GET /v1/recipients`.
`to` is a phone number (spoken digits are fine) or an exact saved recipient name; ten-digit numbers
get `+1` on a US SIM. Hermes may send at most `hermes.dailySendLimit` texts a day (default 100).

## Setup

On the Hermes host, as its user:

```sh
# 1. MCP server and skill (from this repository)
rsync -a --exclude tests server/robotos_mcp/ HOST:robotOS-r1/server/robotos_mcp/
rsync -a server/hermes/skills/robotos/ HOST:.hermes/skills/robotos/

# 2. ~/.hermes/.env (0600): API server and the R1 device token (32+ random characters each)
API_SERVER_ENABLED=true
API_SERVER_HOST=127.0.0.1
API_SERVER_PORT=8642
API_SERVER_KEY=…
ROBOTOS_R1_TOKEN=…

# 3. Register the MCP server (answer y to both prompts); the token stays a ${…} reference
hermes mcp add robotos --command /opt/homebrew/bin/python3 --connect-timeout 30 \
  --env PYTHONPATH=$HOME/robotOS-r1 ROBOTOS_R1_URL=http://<R1 tailnet IP>:8765 \
  'ROBOTOS_R1_TOKEN=${ROBOTOS_R1_TOKEN}' ROBOTOS_PROXY=http://127.0.0.1:1056 \
  --args -m server.robotos_mcp
hermes config set mcp_servers.robotos.timeout 90

# 4. The userspace tailscaled gets an outbound proxy so the MCP server can reach the R1:
#    add --outbound-http-proxy-listen=127.0.0.1:1056 to ai.robotos.tailscale's ProgramArguments,
#    then launchctl bootout/bootstrap it.

# 5. Expose Hermes's API to the tailnet only, and run the gateway
tailscale --socket="$HOME/Library/Application Support/robotOS/tailscale/tailscaled.sock" \
  serve --bg --https=443 http://127.0.0.1:8642
hermes gateway install --start-now --start-on-login
```

On the Mac, provision the R1 through the config receiver (keys are never printed):

```json
{
  "activeProvider": "hermes",
  "providers": {"hermes": {"baseUrl": "https://HOST.TAILNET.ts.net/v1", "apiKey": "<API_SERVER_KEY>",
                "model": "hermes-agent", "session": "hermes-session", "timeoutSec": 300}},
  "hermes": {"deviceApi": true, "token": "<ROBOTOS_R1_TOKEN>", "port": 8765, "passthrough": true, "owner": "+1XXXXXXXXXX"},
  "bridge": {"enabled": false}
}
```

```sh
adb shell am broadcast -n dev.r1ptt/.data.ConfigReceiver -a dev.r1ptt.CONFIG --es json_b64 "$(base64 < hermes.json)"
```

The R1 needs the Tailscale app signed in to the same tailnet. Run the MCP tests with
`python3 -m unittest server.robotos_mcp.tests.test_robotos_mcp`.

## Checks

- From the Hermes host: `curl -x http://127.0.0.1:1056 -H "Authorization: Bearer $ROBOTOS_R1_TOKEN" http://<R1 IP>:8765/v1/status`
- Ask Hermes "what's my R1's battery level?": it should call `mcp_robotos_r1_status`.
- `adb logcat -s r1ptt` shows content-free events: `device api: …`, `hermes text: …`.

## Security notes

- The API key gives the R1 your full Hermes agent, tools included. It lives only in the R1's
  encrypted config and is used over tailnet-only HTTPS.
- Hermes can text anyone from your number. `dailySendLimit` bounds a runaway loop; it is not a
  policy filter.
- Passthrough trusts the sender number. SMS caller ID can be spoofed, so anyone spoofing your number
  could talk to your agent. Turn off `hermes.passthrough` if that matters for your Hermes's tools.
- Incoming texts from other people are never forwarded. When Hermes reads them with
  `recent_texts`, the skill tells it to treat them as information, not instructions.

This replaces the earlier [Hermes SMS relay](RELAY.md); its services are stopped and disabled on
the Hermes host.
