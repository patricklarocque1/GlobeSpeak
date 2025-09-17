package com.globespeak.engine.nllb.spm

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencePieceRoundTripTest {
    private fun sp(): SentencePiece {
        // Minimal synthetic vocab covering ASCII and space marker
        val pieces = mutableListOf<String>()
        pieces += listOf("<unk>", "<s>", "</s>")
        pieces += "▁"
        // A few common substrings to allow greedy segmentation
        pieces += listOf("Hello", ",", "how", "are", "you", "?", "▁Hello", "▁how", "▁are", "▁you")
        // Basic latin letters to ensure coverage of arbitrary words
        pieces += ('a'..'z').map { it.toString() }
        pieces += ('A'..'Z').map { it.toString() }
        // Latin accented sample
        pieces += listOf("é", "à")
        // CJK pieces (without spaces)
        pieces += listOf("你", "好")
        return SentencePiece(pieces, bosId = 1, eosId = 2, unkId = 0)
    }

    @Test fun decodeEncode_ascii() {
        val s = sp()
        val txt = "Hello, how are you?"
        val ids = s.encode(txt)
        val back = s.decode(ids)
        val ids2 = s.encode(back)
        assertEquals(ids.toList(), ids2.toList())
    }

    @Test fun decodeEncode_accents() {
        val s = sp()
        val txt = "Café à la carte"
        val ids = s.encode(txt)
        val back = s.decode(ids)
        val ids2 = s.encode(back)
        assertEquals(ids.toList(), ids2.toList())
    }

    @Test fun decodeEncode_cjk() {
        val s = sp()
        val txt = "你好"
        val ids = s.encode(txt)
        val back = s.decode(ids)
        val ids2 = s.encode(back)
        assertEquals(ids.toList(), ids2.toList())
    }
}
