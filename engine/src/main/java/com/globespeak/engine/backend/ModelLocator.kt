package com.globespeak.engine.backend

import android.content.Context
import java.io.File

open class ModelLocator(private val context: Context) {
    open fun baseDir(): File = File(context.filesDir, "models/nllb").apply { mkdirs() }
    open fun modelFile(): File = File(baseDir(), "nllb.onnx")
    open fun tokenizerFile(): File = File(baseDir(), "tokenizer.model")
    open fun hasNllbModel(): Boolean = modelFile().exists() && tokenizerFile().exists()
}
