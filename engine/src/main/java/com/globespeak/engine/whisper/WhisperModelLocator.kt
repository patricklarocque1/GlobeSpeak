package com.globespeak.engine.whisper

import android.content.Context
import java.io.File

/**
 * Resolves Whisper model file locations. The user has to sideload the ONNX assets via the app importer.
 */
class WhisperModelLocator(private val context: Context) {
    private val baseDir: File by lazy { File(context.filesDir, "models/whisper").apply { mkdirs() } }

    fun baseDir(): File = baseDir

    fun initializer(): File = File(baseDir, "Whisper_initializer.onnx")
    fun encoder(): File = File(baseDir, "Whisper_encoder.onnx")
    fun decoder(): File = File(baseDir, "Whisper_decoder.onnx")
    fun cacheInitializer(): File = File(baseDir, "Whisper_cache_initializer.onnx")
    fun cacheInitializerBatch(): File = File(baseDir, "Whisper_cache_initializer_batch.onnx")
    fun detokenizer(): File = File(baseDir, "Whisper_detokenizer.onnx")

    fun hasModel(): Boolean = requiredFiles().all { it.exists() }

    fun requiredFiles(): List<File> = listOf(
        initializer(),
        encoder(),
        decoder(),
        cacheInitializer(),
        cacheInitializerBatch(),
        detokenizer()
    )
}
