package com.globespeak.engine.asr

import java.io.ByteArrayOutputStream

/**
 * Lightweight stub implementation used when Whisper models are unavailable.
 */
class NoOpStreamingAsr(
    private val partialByteThreshold: Int = DEFAULT_PARTIAL_THRESHOLD_BYTES
) : StreamingAsr {
    private val buffer = ByteArrayOutputStream()
    private var bytesSincePartial = 0
    private var emissionSeq = 0

    override fun onPcmChunk(pcm16LeMono16k: ByteArray): AsrPartial? {
        if (pcm16LeMono16k.isEmpty()) return null
        buffer.write(pcm16LeMono16k)
        bytesSincePartial += pcm16LeMono16k.size
        if (bytesSincePartial < partialByteThreshold) return null
        bytesSincePartial = 0
        return AsrPartial(
            text = transcriptForBytes(buffer.size()),
            isStable = false,
            sequenceId = nextSequence()
        )
    }

    override fun finalizeSegment(): AsrFinal {
        val text = transcriptForBytes(buffer.size())
        val seqId = if (text.isEmpty()) emissionSeq else nextSequence()
        reset()
        return AsrFinal(text = text, sequenceId = seqId)
    }

    override fun close() { /* no-op */ }

    private fun transcriptForBytes(bytes: Int): String =
        if (bytes == 0) "" else "TRANSCRIPT(len=$bytes)"

    private fun nextSequence(): Int {
        emissionSeq += 1
        return emissionSeq
    }

    private fun reset() {
        buffer.reset()
        bytesSincePartial = 0
        emissionSeq = 0
    }

    companion object {
        private const val DEFAULT_PARTIAL_THRESHOLD_BYTES = 20_000
    }
}
