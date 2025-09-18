package com.globespeak.engine.asr

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class WhisperCppSmokeTest {

    @Test
    fun loadsModelAndProducesPartialWhenAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val verifier = WhisperBundleVerifier(context)
        val model = verifier.locateBestModel()
        assumeTrue("No Whisper ggml/gguf model present on device; skipping", model != null)

        WhisperCppAsr(model!!).use { asr ->
            val pcm = synthSine()
            repeat(5) {
                val partial = asr.onPcmChunk(pcm)
                if (partial != null) {
                    return@repeat
                }
                SystemClock.sleep(100)
            }
            val finalResult = asr.finalizeSegment()
            assertNotNull(finalResult)
        }
    }

    private fun synthSine(): ByteArray {
        val sampleRate = 16_000
        val durationMs = 200
        val samples = sampleRate * durationMs / 1000
        val buffer = ByteArray(samples * 2)
        val frequency = 440.0
        var idx = 0
        for (n in 0 until samples) {
            val value = (sin(2.0 * PI * frequency * n / sampleRate) * Short.MAX_VALUE).toInt()
            buffer[idx++] = (value and 0xFF).toByte()
            buffer[idx++] = (value shr 8).toByte()
        }
        return buffer
    }
}
