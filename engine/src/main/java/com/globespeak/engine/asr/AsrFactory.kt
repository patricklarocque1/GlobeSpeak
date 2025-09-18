package com.globespeak.engine.asr

import android.content.Context
import android.os.Build
import android.util.Log
import com.globespeak.engine.whisper.WhisperStreamingSession
import kotlin.io.use

/**
 * Chooses the optimal streaming ASR implementation at runtime.
 *
 * Preference order:
 * 1. whisper.cpp JNI backend when a GGML/GGUF model is present and the device supports a 64-bit ABI.
 * 2. Existing ONNX-based Whisper session (batch fallback) when streaming cannot be used.
 * 3. Stub implementation when neither backend is available.
 */
class AsrFactory(context: Context) {

    private val appContext = context.applicationContext ?: context
    private val verifier = WhisperBundleVerifier(appContext)

    fun build(): Result {
        val abiIssue = checkAbi()
        val fallbackMissingModel = fallback(Status.MISSING_MODEL, "Import a Whisper GGML/GGUF model")

        if (abiIssue != null) {
            val fallbackCreator = fallback(Status.UNSUPPORTED_ABI, abiIssue)
            return Result(
                initialStatus = Status.UNSUPPORTED_ABI,
                initialMessage = abiIssue,
                expectedBackend = Backend.WHISPER_ONNX,
                create = { fallbackCreator(abiIssue) },
                fallback = fallbackCreator
            )
        }

        val model = verifier.locateBestModel()
        if (model == null) {
            return Result(
                initialStatus = Status.MISSING_MODEL,
                initialMessage = "Import a Whisper GGML/GGUF model",
                expectedBackend = Backend.WHISPER_ONNX,
                create = { fallbackMissingModel(null) },
                fallback = fallbackMissingModel
            )
        }

        // Try to warm up the whisper.cpp instance once to validate the setup.
        val warmupResult = runCatching {
            WhisperCppAsr(model).use { }
        }

        if (warmupResult.isFailure) {
            val error = warmupResult.exceptionOrNull()
            Log.e(TAG, "whisper.cpp warmup failed", error)
            val message = error?.message ?: "whisper.cpp error"
            val fallbackCreator = fallback(Status.ERROR, message)
            return Result(
                initialStatus = Status.ERROR,
                initialMessage = message,
                expectedBackend = Backend.WHISPER_ONNX,
                create = { fallbackCreator(message) },
                fallback = fallbackCreator
            )
        }

        val fallbackCreator = fallback(Status.ERROR, null)
        return Result(
            initialStatus = Status.OK,
            initialMessage = null,
            expectedBackend = Backend.WHISPER_CPP,
            create = {
                try {
                    AsrInstance(
                        asr = WhisperCppAsr(model),
                        backend = Backend.WHISPER_CPP,
                        status = Status.OK,
                        statusMessage = null
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to initialize whisper.cpp session", t)
                    val message = t.message ?: "whisper.cpp error"
                    fallbackCreator(message)
                }
            },
            fallback = fallbackCreator
        )
    }

    private fun checkAbi(): String? {
        val supported = Build.SUPPORTED_64_BIT_ABIS
        val allowsStreaming = supported.any { it == "arm64-v8a" || it == "x86_64" }
        return if (allowsStreaming) null else "Requires 64-bit ABI (arm64-v8a / x86_64)"
    }

    private fun fallback(status: Status, defaultMessage: String?): (String?) -> AsrInstance {
        val ctx = appContext
        return { providedReason ->
            val message = providedReason ?: defaultMessage
            runCatching {
                AsrInstance(
                    asr = WhisperStreamingSession(ctx),
                    backend = Backend.WHISPER_ONNX,
                    status = status,
                    statusMessage = message
                )
            }.getOrElse { err ->
                Log.w(TAG, "Falling back to stub ASR", err)
                AsrInstance(
                    asr = NoOpStreamingAsr(),
                    backend = Backend.STUB,
                    status = Status.ERROR,
                    statusMessage = err.message ?: message ?: "ASR unavailable"
                )
            }
        }
    }

    data class Result(
        val initialStatus: Status,
        val initialMessage: String?,
        val expectedBackend: Backend,
        val create: () -> AsrInstance,
        val fallback: (String?) -> AsrInstance
    )

    data class AsrInstance(
        val asr: StreamingAsr,
        val backend: Backend,
        val status: Status,
        val statusMessage: String?
    )

    enum class Backend { WHISPER_CPP, WHISPER_ONNX, STUB }

    enum class Status { OK, MISSING_MODEL, UNSUPPORTED_ABI, ERROR }

    companion object {
        private const val TAG = "AsrFactory"

        fun build(context: Context): Result = AsrFactory(context).build()
    }
}
