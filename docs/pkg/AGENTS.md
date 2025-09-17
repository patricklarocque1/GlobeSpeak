# AGENTS.md — GlobeSpeak (Wear OS + Android)

## Project overview
GlobeSpeak is a **hybrid Wear OS translator**. The watch acts as a *thin client* (UI + mic capture) and the phone runs **STT (Whisper)** + **on‑device translation** (NLLB or **ML Kit Translation**) fully offline. The watch and phone communicate over the **Wear OS Data Layer** (audio via `ChannelClient`, translated text via `MessageClient`).

### Modules
- `mobile/` — Android (phone) app. Hosts the **ForegroundService** that performs STT + translation and replies to the watch.
- `wear/` — Wear OS app. Captures audio, shows conversation UI, sends PCM to the phone, receives translations.
- `engine/` — Android **library** exposing a simple facade (`TranslatorEngine`) that wraps Whisper + translation.

## Current backend status
- **Standard (ML Kit)** is the default backend; it auto-manages offline translation models.
- **Advanced (NLLB-ONNX)** is available on capable 64-bit devices (heap ≥256 MB) with UI/log fallbacks when models/capability are missing.
- SentencePiece parsing, greedy ONNX decoding, and backend selection now have unit coverage (tokenizer round-trip, capability matrix, protocol framing, VAD).
- Bench/About tooling ship for backend timing and license visibility.
- Whisper streaming STT runs on the phone using sideloaded ONNX weights, providing partial/final transcripts to the watch.

---

## Mission for coding agents
Implement, maintain, and test a hybrid Wear OS translator. The watch is a thin client (UI + mic), the phone performs **offline** STT/translation and sends results back via the Data Layer. Keep the app **offline‑only**.

## DO (allowed)
- **SPM / ONNX wiring** for the advanced backend (NLLB‑ONNX): tokenizer integration, prompt building, ONNX session creation, greedy/incremental decoding.
- **ML Kit** integration for the standard backend: Language ID + on‑device translation with model download/management.
- Data Layer code: `ChannelClient`  (audio), `MessageClient`  (text/settings).
- Settings & UX: engine toggle (Standard vs Advanced), model import (SAF), readiness checks, and fallback logic.
- Tests: tokenization round‑trip, factory selection matrix, UI state transitions, non‑device benchmarks.

## DON’T (forbidden)
- **No network/cloud translation** or calls that upload user audio/text.
- **No model bundling** in the APK/AAB (large files). Use sideload or in‑app import only.
- **Don’t change** the audio format/signature without updating both watch and phone sides and tests.

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

## Build commands
```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew :engine:test :mobile:test
```

---

## SDK targets (2025)
- **Phone**: `compileSdk=35`, `targetSdk=35` (Android 15)
- **Wear** : `compileSdk=35`, `targetSdk=34` (Wear OS Play policy)
- **minSdk**: `mobile 26+`; `wear 30+` (adjust if you must support Wear OS 2)

---

## Paths & contracts
- Audio: watch → phone via `ChannelClient`  at `"/audio"` ; format **PCM 16‑bit, 16 kHz, mono, LE**.
- Translation text: phone → watch via `MessageClient`  at `"/translation"` .
- Settings sync: `"/settings/target_lang"`  (phone→watch), `"/settings/request"`  (watch→phone).
- Keep both apps’ services **foreground** with visible notifications
- Reconnect with exponential backoff when a node disconnects

---

## File locations (advanced backend)
```
filesDir/models/nllb/
  nllb.onnx
  tokenizer.model
```
Use `ModelLocator`  to resolve paths; do not hardcode external storage.
Download the ONNX + SentencePiece files with the Hugging Face CLI (see README “Advanced Setup” step 0) or use the in-app importer.

## File locations (Whisper STT)
```
filesDir/models/whisper/
  Whisper_initializer.onnx
  Whisper_encoder.onnx
  Whisper_decoder.onnx
  Whisper_cache_initializer.onnx
  Whisper_cache_initializer_batch.onnx
  Whisper_detokenizer.onnx
```
Use `WhisperModelLocator` to resolve paths. Import via device file explorer or ADB.
Languages screen also offers an "Import Whisper file" action through the Storage Access Framework.

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
- Keep commits focused; one logical change each.
- If advanced backend is selected but not available, **fallback** to Standard and log the reason.
- Include screenshots/GIFs for UI changes and benchmark results (if relevant).

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
