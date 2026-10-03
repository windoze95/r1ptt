#!/usr/bin/env bash
# Checks the backends from this computer with the same requests the R1 makes, before any flashing:
# speech-to-text, a streamed chat reply, two-turn memory, and text-to-speech.
#
# Usage: tools/smoke-test.sh [config.json] [--play]     (default config: tools/r1ptt.json)
# Needs: curl, jq; on macOS it records a test question with `say`.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
need curl
need jq

CFG=${1:-$ROOT/tools/r1ptt.json}
PLAY=0
for a in "$@"; do [ "$a" = --play ] && PLAY=1; done
[ -f "$CFG" ] || die "No config at $CFG (copy tools/r1ptt.example.json and fill in your keys)."
mkdir -p "$OUT"

# Merge the example's defaults under the real config, like the app does.
DEFAULTS='{"activeProvider":"openai",
  "providers":{"openai":{"baseUrl":"https://api.openai.com/v1","model":"gpt-6.1-sol","session":"history"},
               "openclaw":{"model":"openclaw/default","session":"openclaw-user"},
               "hermes":{"model":"hermes-agent","session":"hermes-session"}},
  "stt":{"baseUrl":"https://api.openai.com/v1","model":"gpt-transcribe"},
  "tts":{"baseUrl":"https://api.openai.com/v1","model":"gpt-4o-mini-tts-2025-12-15","voice":"marin","enabled":true}}'
C=$(jq -s '.[0] * .[1]' <(echo "$DEFAULTS") "$CFG")
cfg() { jq -r "$1 // empty" <<<"$C"; }

host() { sed -E 's#^[a-z]+://([^/:]+).*#\1#' <<<"$1"; }
# Same rule as the app: an endpoint without its own key borrows a provider key on the same host.
key_for() {
  local url=$1 own=$2 id
  [ -n "$own" ] && { echo "$own"; return; }
  for id in $(jq -r '.providers | keys[]' <<<"$C"); do
    if [ "$(host "$(cfg ".providers.$id.baseUrl")")" = "$(host "$url")" ] && [ -n "$(cfg ".providers.$id.apiKey")" ]; then
      cfg ".providers.$id.apiKey"; return
    fi
  done
}

STT_URL=$(cfg .stt.baseUrl)
STT_KEY=$(key_for "$STT_URL" "$(cfg .stt.apiKey)")

say "1. Speech-to-text ($(cfg .stt.model) at $(host "$STT_URL"))"
if command -v say >/dev/null && command -v afconvert >/dev/null; then
  say "   recording a test question with macOS speech"
  command say -o "$OUT/sample.aiff" "What is the capital of France?"
  afconvert -f m4af -d aac@16000 -c 1 "$OUT/sample.aiff" "$OUT/sample.m4a"
fi
[ -f "$OUT/sample.m4a" ] || die "No $OUT/sample.m4a to transcribe."
HEARD=$(curl -sS --fail-with-body "$STT_URL/audio/transcriptions" -H "Authorization: Bearer $STT_KEY" \
  -F model="$(cfg .stt.model)" -F response_format=json -F file=@"$OUT/sample.m4a" | jq -r .text)
echo "   heard: $HEARD"

chat() { # chat <provider id> <session id> <messages json> → prints streamed text
  local id=$1 sid=$2 msgs=$3 url key model session body
  url=$(cfg ".providers.$id.baseUrl"); key=$(cfg ".providers.$id.apiKey"); model=$(cfg ".providers.$id.model")
  session=$(cfg ".providers.$id.session")
  body=$(jq -n --arg m "$model" --argjson msgs "$msgs" --argjson extra "$(cfg ".providers.$id.extraBody" | grep . || echo '{}')" \
    '{model:$m, stream:true, messages:$msgs} * $extra')
  [ "$session" = openclaw-user ] && body=$(jq --arg u "r1ptt-$sid" '. + {user:$u}' <<<"$body")
  local hdr=()
  [ "$session" = hermes-session ] && hdr=(-H "X-Hermes-Session-Id: r1ptt-$sid")
  curl -sSN --fail-with-body "$url/chat/completions" -H "Authorization: Bearer $key" -H 'Content-Type: application/json' \
    ${hdr[@]+"${hdr[@]}"} -d "$body" |
    sed -n 's/^data: //p' | grep -v '^\[DONE\]' | jq -rj '.choices[0].delta.content // empty' 2>/dev/null
  echo
}

STYLE='{"role":"system","content":"Be brief: one short sentence, plain text."}'
for id in $(jq -r '.providers | keys[]' <<<"$C"); do
  # Test the active provider, plus any other that has a real key filled in.
  KEY=$(cfg ".providers.$id.apiKey")
  case "$KEY" in *REPLACE*) KEY="" ;; esac
  [ "$id" = "$(cfg .activeProvider)" ] || [ -n "$KEY" ] || continue
  say "2. Chat via $id ($(cfg ".providers.$id.model"))"
  printf '   reply: '
  chat "$id" "smoke$$" "[$STYLE,{\"role\":\"user\",\"content\":$(jq -Rn --arg t "$HEARD" '$t')}]"

  say "3. Memory via $id"
  SID="mem$$$RANDOM"
  if [ "$(cfg ".providers.$id.session")" = history ]; then
    printf '   reply: '
    chat "$id" "$SID" "[$STYLE,{\"role\":\"user\",\"content\":\"My name is Sam.\"},{\"role\":\"assistant\",\"content\":\"Nice to meet you, Sam.\"},{\"role\":\"user\",\"content\":\"What is my name?\"}]"
  else
    chat "$id" "$SID" "[$STYLE,{\"role\":\"user\",\"content\":\"My name is Sam. Just say ok.\"}]" >/dev/null
    printf '   reply: '
    chat "$id" "$SID" "[$STYLE,{\"role\":\"user\",\"content\":\"What is my name?\"}]"
  fi
  echo "   (the reply should mention Sam)"
done

TTS_URL=$(cfg .tts.baseUrl)
say "4. Text-to-speech ($(cfg .tts.model), voice $(cfg .tts.voice))"
curl -sS --fail-with-body "$TTS_URL/audio/speech" -H "Authorization: Bearer $(key_for "$TTS_URL" "$(cfg .tts.apiKey)")" \
  -H 'Content-Type: application/json' \
  -d "$(jq -n --arg m "$(cfg .tts.model)" --arg v "$(cfg .tts.voice)" '{model:$m, voice:$v, input:"Paris is the capital of France.", response_format:"pcm"}')" \
  -o "$OUT/tts.pcm"
BYTES=$(wc -c < "$OUT/tts.pcm" | tr -d ' ')
echo "   got $BYTES bytes of 24 kHz PCM (~$((BYTES / 48000)) s of speech)"
if [ "$PLAY" = 1 ] && command -v afplay >/dev/null && command -v node >/dev/null; then
  node -e '
    const fs = require("fs"); const pcm = fs.readFileSync(process.argv[1]); const h = Buffer.alloc(44);
    h.write("RIFF",0); h.writeUInt32LE(36+pcm.length,4); h.write("WAVEfmt ",8); h.writeUInt32LE(16,16);
    h.writeUInt16LE(1,20); h.writeUInt16LE(1,22); h.writeUInt32LE(24000,24); h.writeUInt32LE(48000,28);
    h.writeUInt16LE(2,32); h.writeUInt16LE(16,34); h.write("data",36); h.writeUInt32LE(pcm.length,40);
    fs.writeFileSync(process.argv[2], Buffer.concat([h, pcm]));' "$OUT/tts.pcm" "$OUT/tts.wav"
  afplay "$OUT/tts.wav"
fi
say "All checks ran."
