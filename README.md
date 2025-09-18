# GlobeSpeak — Hybrid Wear OS Translator (Phone‑Tethered)

**GlobeSpeak** translates on your wrist while keeping the heavy work on your phone. The watch streams audio to the phone over the **Wear OS Data Layer**; the phone performs on‑device **speech‑to‑text** and **translation**, then sends the result back.

## Backends
- **Standard (default): ML Kit Translation + Language ID** — small, fast, offline after model download; supports 50+ languages.
- **Advanced (optional): NLLB‑ONNX** — larger model, higher accuracy on capable devices; runs fully offline once models are sideloaded.
  - Requires a compatible ONNX model and a matching **SentencePiece** tokenizer (`tokenizer.model`).

> You can switch engines in **Settings → Languages → Engine**. If a device isn’t capable or models are missing, GlobeSpeak falls back to **Standard** and shows “fallback” in the UI.

---

## Advanced (NLLB‑ONNX) Setup

### 0) Download the model + tokenizer
Grab an NLLB ONNX export (for example [`facebook/nllb-200-distilled-600M`](https://huggingface.co/facebook/nllb-200-distilled-600M)).

Using the Hugging Face CLI:
```bash
huggingface-cli download facebook/nllb-200-distilled-600M --include "*nllb.onnx" "*tokenizer.model" --local-dir ./nllb-download
```
The command creates `./nllb-download/` containing the ONNX model and SentencePiece tokenizer.

### 1) Device capability
Advanced is intended for **arm64** or **x86_64** devices with ample RAM. The app checks capability (RAM/ABI) before activating; otherwise it falls back to Standard.

### 2) Files & paths (SPM tokenizer + ONNX)
Place the following files in the app’s private storage:

```
/Android/data/<package>/files/models/nllb/
  ├─ nllb.onnx          # the translation model
  └─ tokenizer.model    # SentencePiece model for NLLB
```

- In‑app path helper: `filesDir/models/nllb/` .
- Example package: `com.globespeak.mobile`  → `/Android/data/com.globespeak.mobile/files/models/nllb/` .

### 3) Import options
**A) In‑app import (recommended):**  
Settings → “Advanced model” → **Import ONNX** / **Import Tokenizer** (uses Storage Access Framework).

**B) ADB push (developer):**
```bash
adb push nllb.onnx /sdcard/Android/data/com.globespeak.mobile/files/models/nllb/nllb.onnx
adb push tokenizer.model /sdcard/Android/data/com.globespeak.mobile/files/models/nllb/tokenizer.model
```
> On modern Android, `/Android/data`  access may be gated; the in‑app importer is safer.

### 4) Expected sizes & resources
- **Model size:** dependent on the variant you choose; expect **hundreds of MB** (order‑of‑magnitude).  
- **Tokenizer:** typically small (**a few MB**).  
- **Runtime RAM:** plan for **hundreds of MB** during inference depending on model and sequence length.  
- **Battery/thermals:** advanced inference is CPU‑intensive; prefer short utterances.

### 5) Verifying the model
After both files are present, the app performs a lightweight session init and shows **Model OK** on the Languages screen. If loading fails, you’ll see a clear error and the app will continue using **Standard** (ML Kit).

### 6) Benchmark (optional)
Open **Bench** (dev-only nav) to compare Standard vs Advanced:
- Enter text, select target language, run both paths.  
- Shows which backend ran and elapsed time.

---

## Whisper (Streaming STT) Setup

Whisper runs on the phone and powers streaming speech-to-text. The pipeline expects the ONNX export from RTranslator/Whisper:

```
/Android/data/<package>/files/models/whisper/
  ├─ Whisper_initializer.onnx
  ├─ Whisper_encoder.onnx
  ├─ Whisper_decoder.onnx
  ├─ Whisper_cache_initializer.onnx
  ├─ Whisper_cache_initializer_batch.onnx
  └─ Whisper_detokenizer.onnx
```

1. **Download** the bundle (e.g. from [RTranslator releases](https://github.com/niedev/RTranslator/releases)) or via the Hugging Face CLI.
   ```bash
   huggingface-cli download niedev/whisper-small-android --include "Whisper_*.onnx" --local-dir ./whisper-download
   ```
2. **Copy** the files into `filesDir/models/whisper/` using ADB, the device file explorer, or the in-app importer (**Settings → Languages → Whisper → Import Whisper file**).
3. **Verify** on the Languages screen (Whisper status shows “Found” once all files are present). The translation service will switch to Whisper automatically.

### Why whisper.cpp for streaming

- **Real-time cadence:** GlobeSpeak mirrors the [`whisper.cpp` streaming example](https://github.com/ggerganov/whisper.cpp/tree/master/examples/stream) by sliding a 5 s window every ~0.25–0.5 s. That keeps latency low without restarting the model per partial update.
- **Quantised models supported:** whisper.cpp happily loads GGML/GGUF quantised checkpoints, which dramatically reduce RAM/CPU while staying offline.
- **Canonical mel front-end:** the JNI bridge locks in Whisper’s published parameters (`sample_rate=16_000`, `n_fft=400`, `n_mels=80`, `hop_length=160`). A 30 s window therefore covers 3,000 frames — exactly what OpenAI and whisper.cpp expect.
- **Back-pressure guardrails:** if decoding lags, the native ring buffer drops the oldest samples (with a warning) so the app never deadlocks.

---

## Standard (ML Kit) Notes
- First translation requires a one-time **on-device** model download for the chosen target language (Wi-Fi-only toggle available).  
- After download, translation is fully offline.  
- Language detection (Lang ID) is used to choose the source automatically.

---

## Build & Run (quick)
```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew :mobile:installDebug :wear:installDebug
```

## Platform targets & Play policy
- **Phone (mobile module):** `compileSdk=35`, `targetSdk=35` — compliant with Google Play’s 2025 requirement for new and updated apps.
- **Watch (wear module):** `compileSdk=35`, `targetSdk=34` — aligned with the Wear OS policy (API 34+).
- Document SDK bumps in the README so the split policy remains visible to future maintainers.

## Data Layer contract
- Audio (watch → phone): `ChannelClient` at `/audio/pcm16` — **PCM 16‑bit, 16 kHz, mono, LE**, ~8 KB (~250 ms) frames with header `[seq:int][ts:long][size:int]` (LE). `ChannelClient` is the Wear OS‑recommended API for continuously streamed payloads; stay within the 4–32 KB chunk window for robust transport over Bluetooth/Wi‑Fi (see the [Wear OS data layer guide](https://developer.android.com/training/wearables/data-layer)).
- Text (phone → watch): `MessageClient` at `/text/out` — JSON: `{ "type":"partial|final", "text":"...", "seq":N }`.
- Control: `MessageClient` at `/control/handshake` and `/control/heartbeat` (ping/pong).
- State: `DataClient` at `/settings/target_lang` and `/engine/state`, plus `MessageClient` at `/status/asr` to surface ASR availability banners on the watch.

## License & notices
See **NOTICE** for bundled components and attributions.
- Foreground services: Phone and watch run as **microphone** foreground services. Never auto-start them from `BOOT_COMPLETED`.
- Loopback demo: On the phone app Dashboard, enter text and tap Translate — it sends the output to the watch at `/text/out`.
