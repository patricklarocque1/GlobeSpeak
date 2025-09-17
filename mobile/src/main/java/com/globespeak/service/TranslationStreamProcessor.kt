package com.globespeak.service

import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.asr.StreamingAsr
import com.globespeak.engine.asr.AsrPartial
import com.globespeak.engine.asr.AsrFinal
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.globespeak.shared.Bridge
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class TranslationStreamProcessor(
    private val translator: TranslatorEngine,
    private val getTargetLanguage: suspend () -> String,
    private val sendText: suspend (type: String, text: String, seq: Int) -> Unit,
    private val log: (String, String, LogLine.Kind) -> Unit = LogBus::log
) {

    suspend fun process(input: DataInputStream, asr: StreamingAsr): Int {
        var totalFrames = 0
        var lastSeqFromHeader = 0
        try {
            while (true) {
                val header = ByteArray(HEADER_BYTES)
                val read = input.read(header)
                if (read <= 0) break
                if (read < header.size) break

                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val seq = bb.int
                @Suppress("UNUSED_VARIABLE")
                val ts = bb.long
                val size = bb.int
                if (size <= 0 || size > MAX_PAYLOAD_BYTES) break

                val payload = ByteArray(size)
                input.readFully(payload)
                totalFrames += 1
                lastSeqFromHeader = seq
                log(TAG, "Frame seq=$seq bytes=$size", LogLine.Kind.DATALAYER)

                val partial = asr.onPcmChunk(payload)
                sendPartialIfNeeded(partial, seq)
            }

            val finalResult = asr.finalizeSegment()
            if (finalResult.text.isNotEmpty()) {
                val seqId = if (finalResult.sequenceId != 0) finalResult.sequenceId else lastSeqFromHeader
                val translated = translate(finalResult.text)
                sendText("final", translated, seqId)
                log(TAG, "Final seq=$seqId length=${translated.length}", LogLine.Kind.ENGINE)
            }
            log(TAG, "Stream completed frames=$totalFrames", LogLine.Kind.DATALAYER)
        } finally {
            asr.close()
        }
        return totalFrames
    }

    private suspend fun sendPartialIfNeeded(partial: AsrPartial?, defaultSeq: Int) {
        if (partial == null || partial.text.isEmpty()) return
        val seqId = if (partial.sequenceId != 0) partial.sequenceId else defaultSeq
        val translated = translate(partial.text)
        sendText("partial", translated, seqId)
        log(TAG, "Partial seq=$seqId length=${translated.length}", LogLine.Kind.ENGINE)
    }

    private suspend fun translate(text: String): String {
        val target = runCatching { getTargetLanguage() }.getOrDefault(Bridge.DEFAULT_TARGET_LANG)
        runCatching { translator.ensureModel(target) }
        return translator.translate(text, source = "auto", target = target)
    }

    companion object {
        private const val TAG = "TranslationService"
        private const val HEADER_BYTES = 16
        private const val MAX_PAYLOAD_BYTES = 256_000
    }
}
