package com.globespeak.engine.backend

import android.content.Context
import com.globespeak.engine.TranslationBackend
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NllbOnnxTranslationBackend(
    private val app: Context,
    private val models: ModelLocator = ModelLocator(app)
) : TranslationBackend {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    @Volatile private var session: OrtSession? = null

    override suspend fun supportedLanguageTags(): Set<String> = withContext(Dispatchers.Default) {
        NllbLang.map.keys
    }

    override suspend fun isModelDownloaded(langTag: String): Boolean = withContext(Dispatchers.IO) {
        models.hasNllbModel()
    }

    override suspend fun downloadModel(langTag: String, requireWifi: Boolean) {
        // For now require sideloaded models
        if (!models.hasNllbModel()) {
            throw IllegalStateException("NLLB model not found in ${models.baseDir().absolutePath}")
        }
    }

    override suspend fun deleteModel(langTag: String) {
        withContext(Dispatchers.IO) {
            runCatching { models.modelFile().delete() }
            runCatching { models.tokenizerFile().delete() }
            session?.close(); session = null
        }
    }

    private fun ensureSession() {
        if (session == null) {
            synchronized(this) {
                if (session == null) {
                    val opts = OrtSession.SessionOptions()
                    session = env.createSession(models.modelFile().absolutePath, opts)
                }
            }
        }
    }

    override suspend fun translate(
        text: String,
        targetLangTag: String,
        sourceLangTagOrNull: String?
    ): String = withContext(Dispatchers.Default) {
        require(models.hasNllbModel()) { "NLLB model missing" }
        ensureSession()

        // Placeholder: In a full implementation, tokenize with NLLB tokenizer and run the model.
        // For now return a tagged string to show backend selection worked.
        val src = sourceLangTagOrNull ?: LangId.detectOrDefault(text)
        val tgt = targetLangTag
        "[nllb $src->$tgt] $text"
    }
}
