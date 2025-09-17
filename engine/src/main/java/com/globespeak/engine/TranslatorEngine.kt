package com.globespeak.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface TranslationBackend {
    suspend fun supportedLanguageTags(): Set<String>
    suspend fun isModelDownloaded(langTag: String): Boolean
    suspend fun downloadModel(langTag: String, requireWifi: Boolean = true)
    suspend fun deleteModel(langTag: String)
    suspend fun translate(text: String, targetLangTag: String, sourceLangTagOrNull: String? = null): String
}

private class StubTranslationBackend : TranslationBackend {
    override suspend fun supportedLanguageTags(): Set<String> = setOf("en", "fr", "es")
    override suspend fun isModelDownloaded(langTag: String): Boolean = true
    override suspend fun downloadModel(langTag: String, requireWifi: Boolean) {}
    override suspend fun deleteModel(langTag: String) {}
    override suspend fun translate(text: String, targetLangTag: String, sourceLangTagOrNull: String?): String {
        val src = sourceLangTagOrNull ?: "auto"
        return "[$src->$targetLangTag] $text"
    }
}

/**
 * TranslatorEngine facade for offline STT + translation.
 *
 * - Keeps existing APIs for transcribe + translate(source,target)
 * - Adds ML Kit powered backend via [TranslationBackend]
 */
class TranslatorEngine(private val backend: TranslationBackend = StubTranslationBackend()) {
    /**
     * Transcribe PCM 16-bit, 16kHz, mono, little-endian audio to text.
     * Stub for now.
     */
    suspend fun transcribePcm16LeMono16k(pcm: ByteArray): String = withContext(Dispatchers.Default) {
        "TRANSCRIPT(len=${pcm.size})"
    }

    /**
     * Back-compat: translate text from source -> target.
     * If source is "auto", uses language identification when available.
     */
    suspend fun translate(text: String, source: String, target: String): String {
        val srcOpt = if (source.equals("auto", ignoreCase = true)) null else source
        return backend.translate(text, target, srcOpt)
    }

    // New convenience APIs used by UI
    suspend fun ensureModel(target: String, requireWifi: Boolean = true) = backend.downloadModel(target, requireWifi)
    suspend fun isModelReady(target: String) = backend.isModelDownloaded(target)
    suspend fun deleteModel(target: String) = backend.deleteModel(target)
    suspend fun supportedLanguages(): Set<String> = backend.supportedLanguageTags()
}
