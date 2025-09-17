package com.globespeak.engine.nllb

import com.globespeak.engine.backend.NllbLang

object PromptBuilder {
    /**
     * Builds prompt token IDs given tokenizer IDs for BOS/EOS are already embedded by tokenizer.
     * For NLLB-like models, target language token is usually required at the beginning.
     * Here we assume tokenizer will map the string token like "<2fra_Latn>" to an ID if present in vocab.
     */
    fun applyTargetTag(rawIds: IntArray, targetLangTag: String, tokenToId: (String) -> Int?): IntArray {
        val tgtCode = NllbLang.toNllb(targetLangTag)
        val langToken = "<2${tgtCode}>"
        val tagId = tokenToId(langToken)
        return if (tagId != null) intArrayOf(tagId) + rawIds else rawIds
    }
}
