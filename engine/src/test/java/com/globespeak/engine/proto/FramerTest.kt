package com.globespeak.engine.proto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FramerTest {
    @Test
    fun header_is_little_endian_and_correct_length() {
        val payload = ByteArray(1024) { it.toByte() }
        val seq = 42
        val ts = 1690000000000L
        val framed = AudioFramer.frame(seq, ts, payload)
        val bb = ByteBuffer.wrap(framed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(seq, bb.int)
        assertEquals(ts, bb.long)
        assertEquals(payload.size, bb.int)
        assertEquals(AudioFramer.HEADER_SIZE + payload.size, framed.size)
    }
}
