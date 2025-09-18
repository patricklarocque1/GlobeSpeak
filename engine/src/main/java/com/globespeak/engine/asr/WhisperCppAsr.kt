package com.globespeak.engine.asr

import android.os.SystemClock
import com.globespeak.engine.asr.nativebridge.WhisperBridge
import java.io.Closeable
import java.io.File
private const val SAMPLE_RATE_HZ = 16_000
private const val PARTIAL_THROTTLE_MS = 250L

class WhisperCppAsr(
    modelFile: File
) : StreamingAsr, Closeable {

    private val bridge = WhisperBridge.open(modelFile)
    private var sessionActive = false
    private var emissionSeq = 0
    private var lastPartial: String = ""
    private var lastPartialAt = 0L
    private var reusableShorts = ShortArray(0)

    init {
        ensureSession()
    }

    override fun onPcmChunk(pcm16LeMono16k: ByteArray): AsrPartial? {
        if (pcm16LeMono16k.isEmpty()) return null

        ensureSession()

        val sampleCount = pcm16LeMono16k.size / 2
        if (sampleCount == 0) return null

        val shorts = ensureBuffer(sampleCount)
        decodePcmToShorts(pcm16LeMono16k, shorts, sampleCount)
        bridge.acceptPcm(shorts, sampleCount)

        val now = SystemClock.elapsedRealtime()
        if (now - lastPartialAt < PARTIAL_THROTTLE_MS) {
            return null
        }

        val partial = bridge.pollPartial() ?: return null
        if (partial == lastPartial) return null

        lastPartialAt = now
        lastPartial = partial
        return AsrPartial(
            text = partial,
            isStable = false,
            sequenceId = nextSequence()
        )
    }

    override fun finalizeSegment(): AsrFinal {
        val finalText = if (sessionActive) {
            bridge.stop()
            sessionActive = false
            bridge.pollFinal() ?: ""
        } else {
            ""
        }

        lastPartial = ""
        lastPartialAt = 0L
        val seqId = if (finalText.isEmpty()) emissionSeq else nextSequence()
        emissionSeq = 0

        return AsrFinal(text = finalText, sequenceId = seqId)
    }

    override fun close() {
        bridge.close()
        sessionActive = false
    }

    private fun ensureSession() {
        if (!sessionActive) {
            check(bridge.start(SAMPLE_RATE_HZ)) { "Failed to start Whisper streaming session" }
            sessionActive = true
        }
    }

    private fun nextSequence(): Int {
        emissionSeq += 1
        return emissionSeq
    }

    private fun ensureBuffer(sampleCount: Int): ShortArray {
        if (reusableShorts.size < sampleCount) {
            reusableShorts = ShortArray(sampleCount.coerceAtLeast(reusableShorts.size * 2 + 1))
        }
        return reusableShorts
    }

    private fun decodePcmToShorts(bytes: ByteArray, target: ShortArray, sampleCount: Int) {
        var byteIndex = 0
        for (i in 0 until sampleCount) {
            val lo = bytes[byteIndex].toInt() and 0xFF
            val hi = bytes[byteIndex + 1].toInt()
            byteIndex += 2
            val sample = (hi shl 8) or lo
            target[i] = sample.toShort()
        }
    }
}
