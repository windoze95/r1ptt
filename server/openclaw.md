# Using OpenClaw as the backend

The R1 talks to OpenClaw through the gateway's OpenAI-compatible `/v1/chat/completions` endpoint.
That endpoint is off by default.

## 1. Turn on the endpoint and make the gateway reachable

In `~/.openclaw/openclaw.json` on the machine running OpenClaw:

```json5
{
  gateway: {
    port: 18789,
    bind: "lan",                       // or "tailnet" if the R1 runs Tailscale
    auth: { mode: "token", token: "a-long-random-token" },
    http: { endpoints: { chatCompletions: { enabled: true } } },
  },
}
```

Restart the gateway, then check it from another machine:

```sh
curl -sS http://<gateway-ip>:18789/v1/chat/completions \
  -H 'Authorization: Bearer a-long-random-token' -H 'Content-Type: application/json' \
  -d '{"model":"openclaw/default","messages":[{"role":"user","content":"hi"}]}'
```

## 2. Point the R1 at it

In `tools/r1ptt.json`, then `tools/provision.sh --config tools/r1ptt.json`:

```json
{
  "activeProvider": "openclaw",
  "providers": {
    "openai":   { "apiKey": "sk-..." },
    "openclaw": { "baseUrl": "http://<gateway-ip>:18789/v1", "apiKey": "a-long-random-token", "model": "openclaw/default" }
  }
}
```

Keep the OpenAI key in the config even when OpenClaw is active. The R1 still uses OpenAI for
speech-to-text and text-to-speech. To avoid needing an OpenAI key at all, run your own speech
server instead (see [speaches.md](speaches.md)).

## How conversations work

- The R1 sends only your newest message, plus `user: "r1ptt-<conversation id>"`.
- OpenClaw derives a stable session from that `user` value, so the agent remembers the
  conversation.
- A double-tap on the button starts a new conversation, which gives OpenClaw a new session.
- To use a particular agent, set `model` to `openclaw/<agentId>`.

## Security: the token is an owner key

OpenClaw's docs say this endpoint gives **full operator access**: anyone holding the token can do
anything your agent can do. The R1 has no lock screen, so whoever picks it up can talk to your
agent.

- Give the R1 its own agent with only the tools you're comfortable exposing, and set
  `"model": "openclaw/<that-agent>"`.
- Keep the gateway on your LAN or tailnet. Never expose port 18789 to the internet.
- If the R1 is lost, rotate `gateway.auth.token`.

## Away from home

You have two options:

- **Tailscale on the R1.** Simplest, but it costs some idle battery because it keeps a tunnel
  alive. Use `bind: "tailnet"` or `tailscale: { mode: "serve" }` on the gateway.
- **An HTTPS reverse proxy that checks a credential, e.g. Cloudflare Tunnel + Access.** Put the
  service-token headers in the provider's `headers`:
  ```json
  "headers": { "CF-Access-Client-Id": "...", "CF-Access-Client-Secret": "..." }
  ```
