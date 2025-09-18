package com.globespeak.engine.asr

import android.content.Context
import java.io.File

class WhisperBundleVerifier(context: Context) {
    private val baseDir: File = File(context.filesDir, "models/whisper").apply { mkdirs() }

    fun locateBestModel(): File? =
        candidateModels()
            .sortedByDescending { it.length() }
            .firstOrNull { it.length() >= MIN_STREAMING_MODEL_BYTES }

    fun candidateModels(): List<File> =
        baseDir.listFiles { file ->
            file.isFile &&
                STREAMING_EXTENSIONS.any { file.name.endsWith(it, ignoreCase = true) }
        }?.toList() ?: emptyList()

    companion object {
        private val STREAMING_EXTENSIONS = listOf(".gguf", ".ggml", ".bin")
        private const val MIN_STREAMING_MODEL_BYTES = 50L * 1024 * 1024 // ~50 MB tiny ggml
    }
}
