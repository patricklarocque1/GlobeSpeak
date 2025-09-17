package com.globespeak.engine.mlkit

import com.globespeak.engine.TranslationBackend
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap

class MLKitTranslationBackend(
    private val defaultFallbackSource: String = "en",
    private val lruSize: Int = 4
) : TranslationBackend {

    private val manager = RemoteModelManager.getInstance()
    private val langId = LanguageIdentification.getClient()

    private val cache = object : LinkedHashMap<String, Translator>(lruSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Translator>?): Boolean {
            val shouldEvict = size > lruSize
            if (shouldEvict && eldest != null) {
                try { eldest.value.close() } catch (_: Throwable) {}
            }
            return shouldEvict
        }
    }

    override suspend fun supportedLanguageTags(): Set<String> = withContext(Dispatchers.Default) {
        TranslateLanguage.getAllLanguages().toSet()
    }

    override suspend fun isModelDownloaded(langTag: String): Boolean = withContext(Dispatchers.IO) {
        val code = TranslateLanguage.fromLanguageTag(langTag) ?: langTag
        val downloaded = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
        downloaded.any { it.language == code }
    }

    override suspend fun downloadModel(langTag: String, requireWifi: Boolean): Unit = withContext(Dispatchers.IO) {
        val code = TranslateLanguage.fromLanguageTag(langTag) ?: langTag
        val model = TranslateRemoteModel.Builder(code).build()
        val conditions = DownloadConditions.Builder().apply { if (requireWifi) requireWifi() }.build()
        manager.download(model, conditions).await()
        Unit
    }

    override suspend fun deleteModel(langTag: String) = withContext(Dispatchers.IO) {
        val code = TranslateLanguage.fromLanguageTag(langTag) ?: langTag
        val model = TranslateRemoteModel.Builder(code).build()
        manager.deleteDownloadedModel(model).await()
        Unit
    }

    override suspend fun translate(
        text: String,
        targetLangTag: String,
        sourceLangTagOrNull: String?
    ): String = withContext(Dispatchers.IO) {
        val tgt = TranslateLanguage.fromLanguageTag(targetLangTag) ?: targetLangTag
        val src = sourceLangTagOrNull?.let { TranslateLanguage.fromLanguageTag(it) } ?: identify(text) ?: defaultFallbackSource

        val key = "$src->$tgt"
        val translator = synchronized(cache) {
            cache[key] ?: run {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
                val client = Translation.getClient(options)
                cache[key] = client
                client
            }
        }

        // Ensure required models are on-device; downloads models if needed (typically both languages)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        translator.translate(text).await()
    }

    private suspend fun identify(text: String): String? = withContext(Dispatchers.IO) {
        val code = langId.identifyLanguage(text).await()
        if (code == "und") null else code
    }
}
