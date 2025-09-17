package com.globespeak.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TranslatorEngine facade for offline STT + translation.
 *
 * Initial stub implementation returns deterministic strings; replace with
 * Whisper STT and ML Kit Translation when integrating real models.
 */
class TranslatorEngine {
    /**
     * Transcribe PCM 16-bit, 16kHz, mono, little-endian audio to text.
     */
    suspend fun transcribePcm16LeMono16k(pcm: ByteArray): String = withContext(Dispatchers.Default) {
        // Minimal stub: return a fixed transcription indicating the bytes length
        // to make testing and wiring easier without real models.
        "TRANSCRIPT(len=${pcm.size})"
    }

    /**
     * Translate text from source -> target language.
     */
    suspend fun translate(text: String, source: String, target: String): String = withContext(Dispatchers.Default) {
        // Minimal stub: mark the translation with language codes.
        "[$source->$target] $text"
    }
}

