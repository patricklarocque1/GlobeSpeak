package com.globespeak.engine.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WhisperLanguagesTest {
    @Test
    fun englishTokenIsStable() {
        assertEquals(50259, WhisperLanguages.tokenFor("en"))
    }

    @Test
    fun languageLookupIsCaseInsensitive() {
        val tokenLower = WhisperLanguages.tokenFor("fr")
        val tokenUpper = WhisperLanguages.tokenFor("FR")
        assertEquals(tokenLower, tokenUpper)
    }

    @Test
    fun unknownLanguageFallsBackToEnglish() {
        val token = WhisperLanguages.tokenFor("zz")
        assertEquals(WhisperLanguages.tokenFor("en"), token)
    }
}
