package com.globespeak.engine.asr.nativebridge

import java.io.Closeable
import java.io.File

internal class WhisperBridge private constructor(
    private var handle: Long
) : Closeable {

    fun start(sampleRate: Int): Boolean {
        check(handle != 0L) { "WhisperBridge released" }
        return nativeStart(handle, sampleRate)
    }

    fun acceptPcm(samples: ShortArray, length: Int) {
        if (handle == 0L || length <= 0) return
        nativeAcceptPcm(handle, samples, length)
    }

    fun pollPartial(): String? {
        if (handle == 0L) return null
        return nativePollPartial(handle)
    }

    fun pollFinal(): String? {
        if (handle == 0L) return null
        return nativePollFinal(handle)
    }

    fun stop() {
        if (handle == 0L) return
        nativeStop(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0
        }
    }

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }

        fun open(model: File): WhisperBridge {
            require(model.exists()) { "Model file missing: ${model.absolutePath}" }
            val ptr = nativeInit(model.absolutePath)
            require(ptr != 0L) { "Failed to initialize whisper.cpp at ${model.absolutePath}" }
            return WhisperBridge(ptr)
        }

        @JvmStatic
        private external fun nativeInit(modelPath: String): Long

        @JvmStatic
        private external fun nativeStart(handle: Long, sampleRate: Int): Boolean

        @JvmStatic
        private external fun nativeAcceptPcm(handle: Long, samples: ShortArray, length: Int)

        @JvmStatic
        private external fun nativePollPartial(handle: Long): String?

        @JvmStatic
        private external fun nativePollFinal(handle: Long): String?

        @JvmStatic
        private external fun nativeStop(handle: Long)

        @JvmStatic
        private external fun nativeRelease(handle: Long)
    }
}
