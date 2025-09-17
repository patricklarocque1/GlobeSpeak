package com.globespeak.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorEngineTest {
    @Test
    fun `transcribe stub returns deterministic text`() = runBlocking {
        val engine = TranslatorEngine()
        val pcm = ByteArray(16) { it.toByte() }
        val transcript = engine.transcribePcm16LeMono16k(pcm)
        assertEquals("TRANSCRIPT(len=16)", transcript)
    }

    @Test
    fun `translate stub wraps text with languages`() = runBlocking {
        val engine = TranslatorEngine()
        val result = engine.translate("hello", source = "en", target = "es")
        assertEquals("[en->es] hello", result)
    }
}
