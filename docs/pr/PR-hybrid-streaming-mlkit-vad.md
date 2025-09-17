# PR: Hybrid streaming engine + VAD + ML Kit + connection health (Watch ⇄ Phone)

## Summary

Production-focused upgrade that keeps the hybrid architecture, adds low-latency streaming with VAD, moves MT to on-device ML Kit, and hardens connection health + logging + tests. Uses the Wear OS Data Layer with the right clients (Channel for audio, Message for control, Data for state). Complies with Android 14+ foreground service type rules.

## Motivation

- Reduce latency and battery by streaming only voiced audio (VAD).
- Make translation work reliably offline via ML Kit on-device models with on-demand downloads.
- Improve observability (heartbeats, structured logs).
- Ensure FGS policies are clean on Android 14+ (declare proper service types, combine when needed).

## What Changed

- Data Layer protocol:
  - ChannelClient for `/audio/pcm16` streaming (watch → phone).
  - MessageClient for `/control/handshake`, `/control/heartbeat`, `/text/out`.
  - DataClient for `/engine/state` and `/settings/target_lang`.
- Phone (`TranslationService`): Foreground data-processing service (`dataSync`) that ingests PCM frames, runs streaming ASR (stubbed), translates via ML Kit, and emits partial/final results via `/text/out`. Heartbeat replies and `/engine/state` publishing included. Guarded FGS starts.
- Watch (`AudioCaptureService`): Foreground service (`microphone|dataSync`) that captures 16k/mono PCM, gates with VAD, frames ~320 ms chunks with seq+ts header, opens `/audio/pcm16` channel, and sends handshake + 5s heartbeat. UI renders partial/final separately.
- ML Kit translation: On-device translation client; `LanguageManager` prefetches required models.
- Logging: Timber; structured events for channel lifecycle, node IDs, heartbeats, and stage timing.
- Tests: VAD gating, message framing, language model manager.
- Docs: README updated with new contracts & FGS notes.

## Paths & Files

- Protocol constants
  - `wear/src/main/java/com/globespeak/shared/Bridge.kt`
  - `mobile/src/main/java/com/globespeak/shared/Bridge.kt`
- Shared protocol + framing
  - `engine/src/main/java/com/globespeak/engine/proto/Protocol.kt`
  - `engine/src/main/java/com/globespeak/engine/proto/AudioFramer.kt`
- Phone engine service
  - `mobile/src/main/java/com/globespeak/service/TranslationService.kt`
  - `mobile/src/main/AndroidManifest.xml`
  - `mobile/src/main/java/com/globespeak/mobile/GlobeSpeakApp.kt`
- Watch thin client
  - `wear/src/main/java/com/globespeak/service/AudioCaptureService.kt`
  - `wear/src/main/java/com/globespeak/audio/VadGate.kt`
  - `wear/src/main/java/com/globespeak/ui/MainActivity.kt`
  - `wear/src/main/java/com/globespeak/ui/WatchViewModel.kt`
  - `wear/src/main/AndroidManifest.xml`
  - `wear/src/main/java/com/globespeak/GlobeSpeakWearApp.kt`
- ML Kit
  - `engine/src/main/java/com/globespeak/engine/LanguageManager.kt`
- Tests
  - `wear/src/test/java/com/globespeak/audio/VadGateTest.kt`
  - `engine/src/test/java/com/globespeak/engine/proto/FramerTest.kt`
  - `engine/src/test/java/com/globespeak/engine/LanguageManagerTest.kt`
- Build & Docs
  - `mobile/build.gradle.kts`
  - `wear/build.gradle.kts`
  - `README.md`

## Data Layer Contract (final)

- ChannelClient: `/audio/pcm16` — PCM 16-bit, 16 kHz, mono, LE, ~320 ms chunks, header `[seq][tsEpochMs][size]` LE.
- MessageClient:
  - Control: `/control/handshake`, `/control/heartbeat` (ping/pong).
  - Text out: `/text/out` with JSON `{ "type":"partial|final", "text":"...", "seq":N }`.
- DataClient:
  - `/settings/target_lang`
  - `/engine/state` (`connected`, `lastHeartbeatAt`, `lastError?`).

## Foreground Services & Permissions

- Phone: `TranslationService` ⇒ `android:foregroundServiceType="dataSync"` (no mic here).
- Watch: `AudioCaptureService` ⇒ `android:foregroundServiceType="microphone|dataSync"` (mic permission required before start).
- Error handling: guard starts for `ForegroundServiceStartNotAllowedException`.

## Risks & Mitigations

- FGS policy regressions → Verified service types, notification presence, runtime mic permission gating.
- Model availability offline → `LanguageManager.ensureModel(...)` prefetches target (and optional source) models.
- Connectivity flakes → heartbeat timeout ⇒ disconnected; channel open retry/backoff to be extended.
- Message sizes → Large data on Channel; control/text on Message; state on Data.

## Test Plan

Unit:
- `VadGateTest` (speech/silence gates, hangover closure)
- `FramerTest` (header fields and length)
- `LanguageManagerTest` (prefetch behavior)

Manual:
- Permission denial path on watch ⇒ clear UX
- Heartbeat timeout ⇒ disconnected state
- Model prefetch on first translate, then offline behavior

## Rollout

- Start internal; monitor channel errors, heartbeat RTT, and stage timing. Ensure Play Console FGS declarations for `dataSync` and `microphone`.

