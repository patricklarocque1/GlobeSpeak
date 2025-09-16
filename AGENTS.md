# AGENTS.md — GlobeSpeak (Wear OS + Android)

## Project overview
GlobeSpeak is a **hybrid Wear OS translator**. The watch acts as a *thin client* (UI + mic capture) and the phone runs **STT (Whisper)** + **on‑device translation** (NLLB or **ML Kit Translation**) fully offline. The watch and phone communicate over the **Wear OS Data Layer** (audio via `ChannelClient`, translated text via `MessageClient`).

### Modules
- `mobile/` — Android (phone) app. Hosts the **ForegroundService** that performs STT + translation and replies to the watch.
- `wear/` — Wear OS app. Captures audio, shows conversation UI, sends PCM to the phone, receives translations.
- `engine/` — Android **library** exposing a simple facade (`TranslatorEngine`) that wraps Whisper + translation.

---

## Local setup
- **JDK 17+**
- **Android Studio (latest stable)** with Android SDKs + Wear OS tools
- **Gradle/AGP** resolved by the project wrapper
- Optional: **ktlint** or Android Studio code style configured to Kotlin defaults

Clone and sync:
```bash
git clone https://github.com/patricklarocque1/globespeak.git
cd globespeak
./gradlew tasks
```

---

## Build & run
Debug build (both apps):
```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
```

Install on connected devices/emulators:
```bash
./gradlew :mobile:installDebug :wear:installDebug
```

Unit tests & lint:
```bash
./gradlew test
./gradlew lint
```

---

## SDK targets (2025)
- **Phone**: `compileSdk=35`, `targetSdk=35` (Android 15)
- **Wear** : `compileSdk=35`, `targetSdk=34` (Wear OS Play policy)
- **minSdk**: `mobile 26+`; `wear 30+` (adjust if you must support Wear OS 2)

---

## Data Layer contracts
- **Audio watch → phone**: PCM **16‑bit, 16 kHz, mono, little‑endian**; chunk 4–32 KB via `ChannelClient`
- **Text phone → watch**: short UTF‑8 messages via `MessageClient` (`/translation` path)
- Keep both apps’ services **foreground** with visible notifications
- Reconnect with exponential backoff when a node disconnects

---

## Engine facade (what agents should call)
```kotlin
class TranslatorEngine {
  suspend fun transcribePcm16LeMono16k(pcm: ByteArray): String
  suspend fun translate(text: String, source: String, target: String): String
}
```
- Initial implementation may use RTranslator’s ONNX pipelines.
- **Recommended**: swap to **ML Kit Translation** for smaller models and speed.

---

## Safe tasks for coding agents
- Wire **ChannelClient** send/receive (watch ↔ phone)
- Implement **VAD** on watch; only stream while speaking
- Integrate **ML Kit Translation** and download language models as needed
- Add error handling, retries, and lifecycle hardening for services
- Write unit tests for `TranslatorEngine` facade and Data Layer helpers
- Improve Compose UI (state badges; conversation list; permissions prompt)

Tasks to avoid unless asked:
- Changing audio format/signature
- Adding cloud APIs or sending speech/text to external services
- Large refactors across all modules at once

---

## CI assumptions
- Gradle builds for `mobile` and `wear`
- JDK 17
- Optional Android SDK setup step if missing on the runner

---

## PR rules
- Title: `[GlobeSpeak] <change>`
- Must pass `test` and `lint`
- For UI changes on watch, include a short screen recording or screenshot
- Keep commits focused; prefer small, reviewable PRs

---

## Security & privacy
- No external network for STT/translation by default (offline only)
- Handle microphone permission on watch responsibly
- Do not log raw audio; redact PII from logs

---

## Commands agents may run
```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew test
./gradlew lint
```
