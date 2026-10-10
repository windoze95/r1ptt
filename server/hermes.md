# Using Hermes Agent as the backend

The R1 talks to Hermes through its API server's OpenAI-compatible `/v1/chat/completions` endpoint.

## 1. Turn on the API server

In `~/.hermes/.env` on the machine running Hermes:

```sh
API_SERVER_ENABLED=true
API_SERVER_KEY=a-long-random-key
API_SERVER_HOST=0.0.0.0        # default 127.0.0.1 only accepts local connections
API_SERVER_PORT=8642
```

Start it with `hermes gateway`, then check it from another machine:

```sh
curl -sS http://<hermes-ip>:8642/v1/chat/completions \
  -H 'Authorization: Bearer a-long-random-key' -H 'Content-Type: application/json' \
  -d '{"model":"hermes-agent","messages":[{"role":"user","content":"hi"}]}'
```

## 2. Point the R1 at it

In `tools/r1ptt.json`, then `tools/provision.sh --config tools/r1ptt.json`:

```json
{
  "activeProvider": "hermes",
  "providers": {
    "openai": { "apiKey": "sk-..." },
    "hermes": { "baseUrl": "http://<hermes-ip>:8642/v1", "apiKey": "a-long-random-key", "model": "hermes-agent" }
  }
}
```

Keep the OpenAI key in the config even when Hermes is active. The R1 still uses OpenAI for
speech-to-text and text-to-speech, because Hermes' API server doesn't accept audio. To avoid
needing an OpenAI key at all, run your own speech server instead (see [speaches.md](speaches.md)).

## How conversations work

- The R1 sends only your newest message, plus the header
  `X-Hermes-Session-Id: r1ptt-<conversation id>`.
- Hermes keeps the transcript under that session, the same way it does for a Telegram chat.
- A double-tap on the button starts a new conversation, which gives Hermes a new session.
- Tool progress events (`hermes.tool.progress`) appear on the R1's status line while the agent
  works.

If that session doesn't keep context for you, switch to the stateless mode Hermes also supports.
Set `"session": "history"` on the hermes provider, and the R1 will send the recent messages itself.

## Security

The API key gives full control of your agent. Keep the server on your LAN or tailnet. For use away
from home, connect through a VPN or an HTTPS reverse proxy that checks a credential. For example,
an access proxy's service-token headers can be set on the provider:

```json
"headers": { "CF-Access-Client-Id": "...", "CF-Access-Client-Secret": "..." }
```

Keep the API server private, and rotate its API key if the R1 is lost.
