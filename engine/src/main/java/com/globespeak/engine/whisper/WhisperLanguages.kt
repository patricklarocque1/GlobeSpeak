package com.globespeak.engine.whisper

/**
 * Maps BCP-47 language tags to Whisper language tokens.
 * Whisper uses tokens in the range [50259, ...] following the training order.
 */
object WhisperLanguages {
    private val LANGUAGES = arrayOf(
        "en","zh","de","es","ru","ko","fr","ja","pt","tr","pl","ca","nl","ar","sv","it","id",
        "hi","fi","vi","he","uk","el","ms","cs","ro","da","hu","ta","no","th","ur","hr","bg",
        "lt","la","mi","ml","cy","sk","te","fa","lv","bn","sr","az","sl","kn","et","mk","br",
        "eu","is","hy","ne","mn","bs","kk","sq","sw","gl","mr","pa","si","km","sn","yo","so",
        "af","oc","ka","be","tg","sd","gu","am","yi","lo","uz","fo","ht","ps","tk","nn","mt",
        "sa","lb","my","bo","tl","mg","as","tt","haw","ln","ha","ba","jw","su","yue"
    )

    private const val START_TOKEN = 50258
    private const val FIRST_LANGUAGE_TOKEN = START_TOKEN + 1

    private val tokenMap: Map<String, Int> = LANGUAGES.withIndex().associate { (idx, lang) ->
        lang to (FIRST_LANGUAGE_TOKEN + idx)
    }

    fun tokenFor(language: String): Int = tokenMap[language.lowercase()] ?: tokenMap.getValue("en")
}
