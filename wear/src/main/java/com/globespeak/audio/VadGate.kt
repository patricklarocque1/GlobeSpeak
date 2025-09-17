package com.globespeak.audio

import kotlin.math.abs

/**
 * Lightweight energy-based VAD.
 * - Computes short-term average absolute amplitude on 20ms windows.
 * - Enters speech when above [enterThreshold] for [enterMs] consecutively.
 * - Stays in speech until below [exitThreshold] for [hangoverMs].
 */
class VadGate(
    private val sampleRate: Int = 16_000,
    private val enterThreshold: Int = 900,   // approx amplitude threshold (PCM16)
    private val exitThreshold: Int = 600,
    private val enterMs: Int = 60,
    private val hangoverMs: Int = 500
) {
    private val windowSamples = (sampleRate * 20) / 1000 // 20ms window
    private var active = false
    private var aboveCount = 0
    private var belowMsAccum = 0

    fun isActive(): Boolean = active

    /**
     * Feed a PCM16 buffer and update internal VAD state.
     * Returns true if currently in speech (gate open).
     */
    fun feed(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return active
        var idx = 0
        var localActive = active
        var localAbove = aboveCount
        var localBelowMs = belowMsAccum

        while (idx < pcm.size) {
            val end = minOf(pcm.size, idx + windowSamples)
            var accum = 0L
            var count = 0
            for (i in idx until end) {
                accum += abs(pcm[i].toInt())
                count++
            }
            val avg = if (count > 0) (accum / count).toInt() else 0

            if (!localActive) {
                if (avg >= enterThreshold) {
                    localAbove += 20
                    if (localAbove >= enterMs) {
                        localActive = true
                        localBelowMs = 0
                    }
                } else {
                    localAbove = 0
                }
            } else {
                if (avg < exitThreshold) {
                    localBelowMs += 20
                    if (localBelowMs >= hangoverMs) {
                        localActive = false
                        localAbove = 0
                    }
                } else {
                    localBelowMs = 0
                }
            }
            idx = end
        }

        active = localActive
        aboveCount = localAbove
        belowMsAccum = localBelowMs
        return active
    }
}

