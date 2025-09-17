package com.globespeak.engine.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.onnxruntime.OrtEnvironment

object NllbValidator {
    /**
     * Attempts to initialize an ONNX session for the sideloaded NLLB model.
     * Returns Pair<ok, message>. On success, message contains the model path.
     */
    suspend fun validate(context: Context): Pair<Boolean, String?> = withContext(Dispatchers.Default) {
        return@withContext try {
            val models = ModelLocator(context)
            if (!models.hasNllbModel()) return@withContext false to "Model files missing"
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(models.modelFile().absolutePath, null)
            session.close()
            true to models.modelFile().absolutePath
        } catch (t: Throwable) {
            false to (t.message ?: t::class.java.simpleName)
        }
    }
}
