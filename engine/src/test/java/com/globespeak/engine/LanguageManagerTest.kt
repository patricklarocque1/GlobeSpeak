package com.globespeak.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeBackend : TranslationBackend {
    val downloaded = mutableListOf<String>()
    override suspend fun supportedLanguageTags(): Set<String> = setOf("en", "fr")
    override suspend fun isModelDownloaded(langTag: String): Boolean = downloaded.contains(langTag)
    override suspend fun downloadModel(langTag: String, requireWifi: Boolean) { downloaded += langTag }
    override suspend fun deleteModel(langTag: String) { downloaded.remove(langTag) }
    override suspend fun translate(text: String, targetLangTag: String, sourceLangTagOrNull: String?): String = text
}

class LanguageManagerTest {
    @Test
    fun ensures_target_and_optional_source() = runBlocking {
        val backend = FakeBackend()
        val mgr = LanguageManager(backend)
        mgr.ensureModel(sourceLang = "en", targetLang = "fr", requireWifi = false)
        assertEquals(listOf("fr", "en"), backend.downloaded)
    }
}

