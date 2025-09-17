package com.globespeak.engine.asr

import java.io.Closeable

/**
 * Contract for streaming automatic speech recognition backed by on-device models.
 *
 * Implementations accept PCM 16-bit, 16 kHz, mono, little-endian audio chunks and may
 * emit throttled partial hypotheses before producing a final transcript when the
 * segment is finalized.
 */
interface StreamingAsr : Closeable {
    /**
     * Feed a chunk of PCM audio into the recognizer.
     *
     * @param pcm16LeMono16k Raw audio samples.
     * @return A partial hypothesis if available, otherwise `null` to signal no update.
     */
    fun onPcmChunk(pcm16LeMono16k: ByteArray): AsrPartial?

    /**
     * Finalize the current utterance and return the last, stable transcript.
     */
    fun finalizeSegment(): AsrFinal
}

/** Represents a streaming ASR hypothesis. */
sealed interface AsrResult {
    val text: String
}

/** A partial hypothesis emitted while streaming audio continues. */
data class AsrPartial(
    override val text: String,
    val isStable: Boolean = false,
    val sequenceId: Int = 0
) : AsrResult

/** The final hypothesis for the current utterance. */
data class AsrFinal(
    override val text: String,
    val sequenceId: Int = 0
) : AsrResult
