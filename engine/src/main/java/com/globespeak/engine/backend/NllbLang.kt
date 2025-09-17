package com.globespeak.engine.backend

object NllbLang {
    val map: Map<String, String> = mapOf(
        "en" to "eng_Latn",
        "fr" to "fra_Latn",
        "es" to "spa_Latn",
        "de" to "deu_Latn",
        "it" to "ita_Latn",
        "pt" to "por_Latn",
        "ru" to "rus_Cyrl",
        "ar" to "arb_Arab",
        "hi" to "hin_Deva",
        "zh" to "zho_Hans",
        "ja" to "jpn_Jpan",
        "ko" to "kor_Hang",
    )
    fun toNllb(bcp47: String?): String = map[bcp47 ?: "en"] ?: "eng_Latn"
}
