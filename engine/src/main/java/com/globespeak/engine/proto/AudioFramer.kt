package com.globespeak.engine.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Frames PCM payloads with a small header: [seq:int][ts:long][size:int] (LE)
 */
object AudioFramer {
    const val HEADER_SIZE = 4 + 8 + 4

    fun frame(seq: Int, tsEpochMs: Long, payload: ByteArray): ByteArray {
        val bb = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(seq)
        bb.putLong(tsEpochMs)
        bb.putInt(payload.size)
        bb.put(payload)
        return bb.array()
    }
}

