package com.globespeak.engine.whisper

import android.content.Context
import com.globespeak.engine.asr.AsrFinal
import com.globespeak.engine.asr.AsrPartial
import com.globespeak.engine.asr.StreamingAsr
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Maintains a streaming Whisper session: buffers PCM, emits partial transcripts and a final transcription.
 */
class WhisperStreamingSession(
    context: Context,
    private val language: String = "en"
) : StreamingAsr {
    private val decoder = WhisperDecoder(WhisperModelLocator(context))
    private val audioBuffer = FloatArrayBuffer()
    private var lastPartial = ""
    private var lastDecodeSamples = 0
    private var emissionSeq = 0

    override fun onPcmChunk(pcm: ByteArray): AsrPartial? {
        if (pcm.isEmpty()) return null
        val floats = pcmToFloats(pcm)
        audioBuffer.addAll(floats)
        val totalSamples = audioBuffer.size
        if (totalSamples - lastDecodeSamples >= PARTIAL_SAMPLE_THRESHOLD) {
            val text = decoder.transcribe(audioBuffer.toFloatArray(), language).trim()
            lastDecodeSamples = totalSamples
            if (text.isNotEmpty() && text != lastPartial) {
                lastPartial = text
                return AsrPartial(text = text, isStable = false, sequenceId = nextEmissionSeq())
            }
        }
        return null
    }

    override fun finalizeSegment(): AsrFinal {
        val audio = audioBuffer.toFloatArray()
        audioBuffer.clear()
        lastDecodeSamples = 0
        lastPartial = ""
        val text = if (audio.isEmpty()) {
            ""
        } else {
            decoder.transcribe(audio, language).trim()
        }
        val seqId = if (text.isEmpty()) emissionSeq else nextEmissionSeq()
        val result = AsrFinal(text = text, sequenceId = seqId)
        emissionSeq = 0
        return result
    }

    override fun close() {
        decoder.close()
    }

    private fun nextEmissionSeq(): Int {
        emissionSeq += 1
        return emissionSeq
    }

    private fun pcmToFloats(pcm: ByteArray): FloatArray {
        val shortCount = pcm.size / 2
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(shortCount)
        for (i in 0 until shortCount) {
            val sample = buffer.short.toInt()
            floats[i] = sample / 32768f
        }
        return floats
    }

    private class FloatArrayBuffer {
        private var data = FloatArray(0)
        private var length = 0

        val size: Int get() = length

        fun addAll(chunk: FloatArray) {
            ensureCapacity(length + chunk.size)
            System.arraycopy(chunk, 0, data, length, chunk.size)
            length += chunk.size
        }

        fun toFloatArray(): FloatArray = data.copyOf(length)

        fun clear() { length = 0 }

        private fun ensureCapacity(required: Int) {
            if (required <= data.size) return
            var newSize = if (data.isEmpty()) DEFAULT_CAPACITY else data.size
            while (newSize < required) newSize = newSize * 2
            data = data.copyOf(newSize)
        }

        companion object {
            private const val DEFAULT_CAPACITY = 16_000
        }
    }

    companion object {
        private const val PARTIAL_SAMPLE_THRESHOLD = 16_000 // 1 second of audio at 16kHz
    }
}
