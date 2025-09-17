package com.globespeak.engine

/**
 * Manages on-device translation models.
 */
class LanguageManager(private val backend: TranslationBackend) {
    suspend fun ensureModel(sourceLang: String?, targetLang: String, requireWifi: Boolean = true) {
        // ML Kit typically ensures both models via translator.downloadModelIfNeeded, but we can prefetch target.
        if (!backend.isModelDownloaded(targetLang)) {
            backend.downloadModel(targetLang, requireWifi)
        }
        if (sourceLang != null && !backend.isModelDownloaded(sourceLang)) {
            // Best-effort: if source is set, prefetch as well
            backend.downloadModel(sourceLang, requireWifi)
        }
    }
}

