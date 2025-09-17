package com.globespeak.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.globespeak.shared.Bridge

val Context.appDataStore by preferencesDataStore(name = "settings")

object SettingsKeys {
    val TARGET_LANG = stringPreferencesKey(Bridge.DS_TARGET_LANG)
    // Back-compat with older key name if present
    val LEGACY_TARGET_LANG = stringPreferencesKey("target_lang")
}

class Settings(private val context: Context) {
    val targetLanguage: Flow<String> = context.appDataStore.data
        .map { prefs ->
            prefs[SettingsKeys.TARGET_LANG]
                ?: prefs[SettingsKeys.LEGACY_TARGET_LANG]
                ?: Bridge.DEFAULT_TARGET_LANG
        }

    suspend fun setTargetLanguage(code: String) {
        context.appDataStore.edit { it[SettingsKeys.TARGET_LANG] = code }
    }
}
