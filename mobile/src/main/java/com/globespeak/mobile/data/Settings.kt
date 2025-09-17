package com.globespeak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

object SettingsKeys {
    val TARGET_LANG = stringPreferencesKey("target_lang")
}

class Settings(private val context: Context) {
    val targetLanguage: Flow<String> = context.dataStore.data
        .map { it[SettingsKeys.TARGET_LANG] ?: "fr" }

    suspend fun setTargetLanguage(code: String) {
        context.dataStore.edit { it[SettingsKeys.TARGET_LANG] = code }
    }
}

