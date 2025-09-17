package com.globespeak.engine.nllb.spm

import java.text.Normalizer

object TextNormalizer {
    fun normalize(input: String): String {
        val nfkc = Normalizer.normalize(input, Normalizer.Form.NFKC)
        return nfkc.trim()
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Postprocess decoded text to collapse spaces and avoid inserting spaces between CJK characters.
     */
    fun postprocessDecoded(input: String): String {
        if (input.isEmpty()) return input
        // Collapse whitespace runs
        val collapsed = input.replace(Regex("\\s+"), " ").trim()
        // Remove spaces between consecutive CJK characters
        val sb = StringBuilder(collapsed.length)
        var i = 0
        while (i < collapsed.length) {
            val ch = collapsed[i]
            if (ch == ' ' && i > 0 && i + 1 < collapsed.length) {
                val prev = collapsed[i - 1]
                val next = collapsed[i + 1]
                if (isCjk(prev) && isCjk(next)) {
                    // skip this space
                    i++
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun isCjk(ch: Char): Boolean {
        val blockName = Character.UnicodeBlock.of(ch)?.toString() ?: return false
        if (blockName == "HIRAGANA" ||
            blockName == "KATAKANA" ||
            blockName == "HANGUL_SYLLABLES" ||
            blockName == "CJK_COMPATIBILITY_IDEOGRAPHS"
        ) {
            return true
        }
        return blockName.startsWith("CJK_UNIFIED_IDEOGRAPHS")
    }
}
