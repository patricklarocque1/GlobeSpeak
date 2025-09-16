
# GlobeSpeak Wear OS Translator — Combined Build Guide (2025‑09‑15)

This document **combines your updated build markdown** (targets, toolchain, dependencies, module configs, Data Layer, engine API, QA/policy, and changes) **with the core architectural blueprint** (hybrid phone‑tethered approach, STT/MT component analysis, and phased roadmap). It’s ready to drop into your repo’s `/docs` folder.

---

## Executive Summary (Architecture & Rationale)

GlobeSpeak is designed as a **hybrid phone‑tethered** system for Wear OS. The **watch** acts as a thin client (UI + mic capture), while the **paired phone** runs the heavy **speech‑to‑text (Whisper)** and **machine translation** models completely **offline**. This layout avoids RAM/CPU/battery limits on the watch, preserves all‑day wearability, and delivers low‑latency translation over Bluetooth via the **Wearable Data Layer**. The hybrid pattern is the pragmatic, officially supported way to move binary audio and short text between Wear and phone, enabling an end‑to‑end offline translation experience.

---

## 1) Targets & SDK levels (2025)

- **Phone app:** `compileSdk = 35`, `targetSdk = 35` (Android 15).  
- **Wear app:** `compileSdk = 35`, `targetSdk = 34` (Wear OS Play requirement).  
- **minSdk** (pick to match your audience):  
  - Wear OS 3+ only: `minSdk 30+` is common.  
  - Need Wear OS 2 support: set `minSdk 25` or higher (trade‑off: more legacy).

> **Rationale:** Phone apps must target **API 35**; Wear apps must target **API 34+**.

---

## 2) Toolchain (current)

- **Android Gradle Plugin (AGP):** 8.13+ (latest stable).  
- **Gradle Wrapper:** 8.14.x (e.g., 8.14.3).  
- **Kotlin:** 2.2.20.

**Top‑level `build.gradle` (excerpt):**
```gradle
plugins {
  id 'com.android.application' version '8.13.0' apply false
  id 'com.android.library'    version '8.13.0' apply false
  id 'org.jetbrains.kotlin.android' version '2.2.20' apply false
}
```

**`gradle/wrapper/gradle-wrapper.properties`:**
```
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-all.zip
```

---

## 3) Dependencies (updated)

### Google Play services — Wearable Data Layer
Use the latest Wearable client:
```gradle
implementation "com.google.android.gms:play-services-wearable:19.0.0"
```

### ML Kit — On‑device Translation
```gradle
implementation "com.google.mlkit:translate:17.0.2" // check for latest before release
```

### Jetpack Compose for Wear OS
Prefer stable Material; optionally try Material3 for Wear (beta):
```gradle
// Stable line
implementation "androidx.wear.compose:compose-material:1.4.0"
implementation "androidx.activity:activity-compose:1.9.3"

// Optional (beta) Material3
// implementation "androidx.wear.compose:compose-material3:1.5.0-beta01"
```

**(Phone) Core Compose via BOM:**
```gradle
implementation platform("androidx.compose:compose-bom:2025.09.00")
implementation "androidx.compose.ui:ui"
implementation "androidx.compose.material3:material3"
implementation "androidx.compose.ui:ui-tooling-preview"
debugImplementation "androidx.compose.ui:ui-tooling"
```

---

## 4) Module configs (snapshots)

### `mobile/build.gradle`
```gradle
android {
  namespace "com.globespeak.mobile"
  compileSdk 35

  defaultConfig {
    applicationId "com.globespeak.mobile"
    minSdk 26
    targetSdk 35
    versionCode 1
    versionName "1.0.0"
  }
}

dependencies {
  implementation "com.google.android.gms:play-services-wearable:19.0.0"
  implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"
  implementation "com.google.mlkit:translate:17.0.2"
  implementation project(":engine")
}
```

### `wear/build.gradle`
```gradle
android {
  namespace "com.globespeak.wear"
  compileSdk 35

  defaultConfig {
    applicationId "com.globespeak.wear"
    minSdk 30      // Wear OS 3+ only; raise/lower per your support strategy
    targetSdk 34   // Play policy for Wear OS
    versionCode 1
    versionName "1.0.0"
  }
}

dependencies {
  implementation "com.google.android.gms:play-services-wearable:19.0.0"
  implementation "androidx.wear.compose:compose-material:1.4.0"
  implementation "androidx.activity:activity-compose:1.9.3"
  implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"
}
```

---

## 5) Data Layer (Channel + Message)

- Stream **watch → phone** audio with **`ChannelClient`**.  
- Send **phone → watch** translation with **`MessageClient`**.  
- Keep both **foreground services** running with visible notifications.

**Audio contract:**
```
PCM 16‑bit, 16 kHz, mono, little‑endian
Chunk size: 4–32 KB (favor low latency)
```

Practical tips:
- Open/close Channels around speech activity to save battery.  
- Use exponential backoff when reconnecting to nodes.  
- Prefer short messages for UI updates; reserve Channels for binary streams.

---

## 6) Engine integration (pattern)

Your `engine` library wraps the (forked) RTranslator Whisper pipeline and translation:

```kotlin
class TranslatorEngine {
  suspend fun transcribePcm16LeMono16k(pcm: ByteArray): String { /* Whisper */ }
  suspend fun translate(text: String, source: String, target: String): String { /* ML Kit / NLLB */ }
}
```

**ML Kit swap‑in (phone):**
```kotlin
val opts = TranslatorOptions.Builder()
  .setSourceLanguage(TranslateLanguage.AUTO_DETECT)
  .setTargetLanguage(TranslateLanguage.FRENCH)
  .build()
val client = Translation.getClient(opts)
client.downloadModelIfNeeded().await()
val translated = client.translate(text).await()
```

---

## 7) Architectural context (from the blueprint)

Three patterns were analyzed for a Wear translator:

- **Standalone on‑watch:** Technically elegant but **not feasible** today due to RAM/CPU limits and battery drain for STT/MT models.  
- **Hybrid phone‑tethered (recommended):** Watch captures and shows; **phone** performs Whisper + translation **offline**, returning text via Data Layer.  
- **Cloud‑connected:** Accurate but adds **latency, cost, and privacy** concerns; also breaks offline use.

This project therefore adopts the **hybrid** design: best balance of feasibility, latency, battery, and offline behavior.

---

## 8) Component choices

### STT (Speech‑to‑Text)
- **Recommended on‑phone:** **OpenAI Whisper** (already integrated in RTranslator via ONNX Runtime). Select model size to balance accuracy vs. speed. Streaming/near‑real‑time setups are possible with VAD in front.
- **Avoid on‑watch** for the main app due to memory/battery constraints.

### MT (Machine Translation)
- **Default (from RTranslator):** **NLLB** via ONNX Runtime.  
- **Recommended enhancement:** **ML Kit On‑device Translation** for smaller per‑language models (~30 MB), faster inference, and simpler integration on Android.

---

## 9) Phased implementation roadmap

**Phase 1 — Phone engine (fork & refactor)**  
- Fork `niedev/RTranslator` and isolate the STT/MT pipeline into an **`engine`** Android library.  
- Create a **ForegroundService** in the phone app to hold models in memory and handle requests.

**Phase 2 — Wear OS thin client**  
- Build a Compose for Wear OS UI (conversation list, state badges, start/stop capture).  
- Implement an **AudioCaptureService** that records PCM and streams via **ChannelClient**.

**Phase 3 — Integration**  
- Phone service listens for Channels, runs STT/MT, and replies via **MessageClient**.  
- Watch listener receives translations and updates UI state.

**Phase 4 — Optimization**  
- Add **VAD** on watch to stream only during speech.  
- Swap **NLLB → ML Kit** for faster translation and lower battery use on phone.  
- Harden reconnection, error handling, and lifecycle.

---

## 10) QA & policy checks (2025)

- Phone module targets **API 35**; Wear module targets **API 34**.  
- Foreground notifications **present and non‑dismissable** while active.  
- Test on devices/emulators **with Google Play services**.  
- Prepare **512×512** Play Store icon and Wear screenshots.

---

## 11) What changed vs. earlier guide

- Updated **Play policy targets** (35 phone / 34 Wear).  
- Bumped **AGP/Gradle/Kotlin** to current stable.  
- **Wearable** client updated to **19.0.0**.  
- Clarified **Compose** guidance (stable Material; optional Material3 beta).  
- Re‑stated Data Layer best practices and **audio format** contract.

---

## 12) Works cited (selection)

- niedev/RTranslator — https://github.com/niedev/RTranslator  
- Whisper (OpenAI) — https://github.com/openai/whisper  
- ML Kit Translation — https://developers.google.com/ml-kit/language/translation/android  
- Wear OS Data Layer overview — https://developer.android.com/training/wearables/data/overview  
- Compose for Wear OS codelab — https://developer.android.com/codelabs/compose-for-wear-os  
- WhisperLive (near‑live Whisper) — https://github.com/collabora/WhisperLive  
- TGwear (Wear OS code patterns) — https://github.com/TGwear/TGwear

---

### Appendix — Minimal watch message listener

```kotlin
class WatchListenerService : WearableListenerService() {
  override fun onMessageReceived(ev: MessageEvent) {
    if (ev.path == "/translation") {
      val text = String(ev.data, Charsets.UTF_8)
      // push into Compose state (ViewModel / singleton)
    }
  }
}
```

