# Self-hosted speech (optional)

By default the R1 uses OpenAI for speech-to-text and text-to-speech. To keep audio on your own
hardware, or to use Hermes without an OpenAI key, run
[Speaches](https://speaches.ai). It is an OpenAI-compatible server that does speech-to-text with
faster-whisper and text-to-speech with Kokoro. A machine with an NVIDIA GPU makes it fast; on a
CPU, short clips are still fine.

```sh
docker compose -f server/speaches.compose.yml up -d
# download a Whisper model and a voice once:
export SPEACHES_BASE_URL=http://localhost:8000
uvx speaches-cli model download Systran/faster-distil-whisper-small.en
uvx speaches-cli model download speaches-ai/Kokoro-82M-v1.0-ONNX
```

Then in `tools/r1ptt.json`:

```json
"stt": { "baseUrl": "http://<speaches-ip>:8000/v1", "model": "Systran/faster-distil-whisper-small.en" },
"tts": { "baseUrl": "http://<speaches-ip>:8000/v1", "model": "speaches-ai/Kokoro-82M-v1.0-ONNX",
         "voice": "af_heart", "format": "wav" }
```

- **`"format": "wav"` is required for Speaches.** Speaches returns WAV or MP3 rather than raw PCM.
  The R1 strips the WAV header and plays the audio at the sample rate the header declares.
- **For languages other than English,** use a multilingual Whisper model such as
  `Systran/faster-whisper-small`.
