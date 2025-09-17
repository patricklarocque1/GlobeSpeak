package com.globespeak.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadGateTest {
    @Test
    fun gate_opens_on_loud_audio_and_closes_after_silence() {
        val vad = VadGate(sampleRate = 16_000, enterThreshold = 800, exitThreshold = 600, enterMs = 40, hangoverMs = 200)

        // Feed silence
        repeat(10) { assertFalse(vad.feed(ShortArray(320))) } // ~200ms

        // Feed speech-like amplitudes
        val loud = ShortArray(320) { 2000.toShort() } // 20ms window
        var active = false
        repeat(10) { active = vad.feed(loud) }
        assertTrue(active)

        // Then silence for > hangover
        var after = true
        repeat(30) { after = vad.feed(ShortArray(320)) }
        assertFalse(after)
    }
}

