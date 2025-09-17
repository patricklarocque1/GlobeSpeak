package com.globespeak.engine.backend

import android.content.Context
import com.globespeak.engine.TranslationBackend
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import com.globespeak.engine.nllb.spm.SentencePiece
import com.globespeak.engine.nllb.PromptBuilder
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
                    val opts = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceAtMost(4))
                        setInterOpNumThreads(1)
                        // Use default optimization level; some artifacts vary in enums
                        // setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ORT_ENABLE_ALL)
                    }
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

        val sp = SentencePiece.load(app)
        val norm = com.globespeak.engine.nllb.spm.TextNormalizer.normalize(text)
        val raw = sp.encode(norm)
        val withTag = com.globespeak.engine.nllb.PromptBuilder.applyTargetTag(raw, targetLangTag) { sp.tokenToId(it) }
        val ids = NllbSession(env, session!!).greedyDecode(withTag, maxNewTokens = 128, eosId = sp.eosId())
        sp.decode(ids)
    }
}
