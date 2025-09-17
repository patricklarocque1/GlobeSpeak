package com.globespeak.engine.nllb

import com.globespeak.engine.backend.NllbLang
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptBuilderTest {
    @Test
    fun targetTag_prepended_and_bos_eos_preserved() {
        // IDs: [BOS=1, 10, 11, EOS=2]
        val raw = intArrayOf(1, 10, 11, 2)
        val tagToken = "<2${NllbLang.toNllb("fr")}>"
        val idMap = mapOf(tagToken to 99)
        val out = PromptBuilder.applyTargetTag(raw, targetLangTag = "fr") { tok -> idMap[tok] }
        // Expect language tag id at index 0, followed by raw sequence
        assertEquals(listOf(99, 1, 10, 11, 2), out.toList())
    }
}

