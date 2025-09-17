# GlobeSpeak — Hybrid Wear OS Translator (Phone‑Tethered)

**GlobeSpeak** lets you converse across languages from your wrist. The **watch** records your speech and displays translations; the **phone** performs offline **speech‑to‑text** (Whisper) and **on‑device translation** (NLLB or ML Kit), returning the result via the **Wear OS Data Layer**.

> Architecture: Watch = UI + mic (thin client). Phone = STT + translation (engine). All offline.

## Quick links
- 📘 **Combined Build Guide:** [docs/GlobeSpeak_Combined_Build_Guide_2025-09-15.md](docs/GlobeSpeak_Combined_Build_Guide_2025-09-15.md)
- 🧾 **PDF Guide:** [docs/GlobeSpeak_Combined_Build_Guide_2025-09-15.pdf](docs/GlobeSpeak_Combined_Build_Guide_2025-09-15.pdf)
- 🤖 **Agents:** see [AGENTS.md](AGENTS.md)

## Modules
- `mobile/` — Phone app with `TranslationService` (engine host)
- `wear/` — Watch app with `AudioCaptureService` + Compose UI
- `engine/` — Android library exposing `TranslatorEngine` facade

## Requirements
- Android Studio (latest), JDK 17+, Android SDKs, Wear OS emulator or watch
- Phone: `compileSdk=35`, `targetSdk=35`
- Wear : `compileSdk=35`, `targetSdk=34`

## Build
```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew :mobile:installDebug :wear:installDebug
```

## Manual End‑to‑End Test
- Watch: launch `GlobeSpeak` and grant microphone permission.
- Phone: no UI is required; the `TranslationService` wakes when the watch opens a channel.
- On the watch, tap Start to begin capture. Speak for a few seconds, then tap Stop.
- The phone receives PCM via a Data Layer Channel (`/audio`), runs the stub engine, and sends text back via `MessageClient` (`/translation`).
- The watch UI displays the latest translated text.

Notes
- Audio: PCM 16‑bit, 16 kHz, mono, little‑endian.
- Offline only: no cloud calls. Engine is a stub; swap in Whisper + ML Kit later.
- Both watch and phone use foreground services while active (visible notifications).

## License
See [LICENSE](LICENSE) and [NOTICE](NOTICE) for details.
