# GlobeSpeak — Hybrid Wear OS Translator (Phone‑Tethered)

**GlobeSpeak** translates on your wrist while keeping the heavy work on your phone. The watch streams audio to the phone over the **Wear OS Data Layer**; the phone performs on‑device **speech‑to‑text** and **translation**, then sends the result back.

## Backends
- **Standard (default): ML Kit Translation + Language ID** — small, fast, offline after model download; supports 50+ languages.
- **Advanced (optional): NLLB‑ONNX** — larger model, higher accuracy on capable devices; runs fully offline once models are sideloaded.

> You can switch engines in **Settings → Languages → Engine**. If a device isn’t capable or models are missing, GlobeSpeak falls back to **Standard** and shows “fallback” in the UI.

---

## Advanced (NLLB‑ONNX) Setup

### 0) Download the model + tokenizer
Download an NLLB ONNX export (e.g. [`facebook/nllb-200-distilled-600M`](https://huggingface.co/facebook/nllb-200-distilled-600M)).

Using Hugging Face CLI:
```bash
huggingface-cli download facebook/nllb-200-distilled-600M --include "*nllb.onnx" "*tokenizer.model" --local-dir ./nllb-download
```
This brings the ONNX graph and SentencePiece tokenizer into `./nllb-download/`.

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
Open **Bench** (from Logs/dev menu) to compare Standard vs Advanced:
- Enter text, select target language, run both paths.  
- Shows which backend ran and elapsed time.

---

## Standard (ML Kit) Notes
- First translation requires a one‑time **on‑device** model download for the chosen target language (Wi‑Fi‑only toggle available).  
- After download, translation is fully offline.  
- Language detection (Lang ID) is used to choose the source automatically.

---

## Build & Run (quick)
```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew :mobile:installDebug :wear:installDebug
```

## Audio contract
- Watch → Phone: **PCM 16‑bit, 16 kHz, mono, little‑endian**, chunk 4–32 KB via `ChannelClient` .  
- Phone → Watch: short UTF‑8 messages via `MessageClient`  at path `/translation` .

## License & notices
See **NOTICE** for bundled components and attributions.
