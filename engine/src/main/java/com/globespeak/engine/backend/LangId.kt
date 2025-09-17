package com.globespeak.engine.backend

import com.google.mlkit.nl.languageid.LanguageIdentification
import kotlinx.coroutines.tasks.await

object LangId {
    suspend fun detectOrDefault(text: String, def: String = "en"): String {
        return try {
            val code = LanguageIdentification.getClient().identifyLanguage(text).await()
            if (code == "und") def else code
        } catch (_: Throwable) { def }
    }
}
