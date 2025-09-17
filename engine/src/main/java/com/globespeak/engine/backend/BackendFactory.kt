package com.globespeak.engine.backend

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import com.globespeak.engine.TranslationBackend
import com.globespeak.engine.mlkit.MLKitTranslationBackend

object BackendFactory {
    private val KEY_ENGINE = stringPreferencesKey("translation_engine")

    fun build(context: Context, prefs: DataStore<Preferences>): TranslationBackend {
        val selected = readEnginePrefSync(prefs)
        val capability = DeviceCapability(context)
        val models = ModelLocator(context)
        return select(selected, capability, models, context)
    }

    data class EngineSelectionInfo(
        val selected: String,   // "standard" | "advanced"
        val active: String,     // "standard" | "advanced"
        val reason: String?     // e.g., "model missing", "device not capable", null if OK
    )

    fun buildWithInfo(context: Context, prefs: DataStore<Preferences>): Pair<TranslationBackend, EngineSelectionInfo> {
        val selectedPref = readEnginePrefSync(prefs) ?: "standard"
        val capability = DeviceCapability(context)
        val models = ModelLocator(context)
        val (active, reason) = resolveActive(selectedPref, capability, models)
        val backend = if (active == "advanced") NllbOnnxTranslationBackend(context, models) else MLKitTranslationBackend()
        return backend to EngineSelectionInfo(selected = selectedPref, active = active, reason = reason)
    }

    internal fun select(
        selected: String?,
        capability: DeviceCapability,
        models: ModelLocator,
        context: Context
    ): TranslationBackend {
        val type = selectType(selected, capability, models)
        return if (type == "nllb") {
            NllbOnnxTranslationBackend(context, models)
        } else {
            MLKitTranslationBackend()
        }
    }

    internal fun selectType(
        selected: String?,
        capability: DeviceCapability,
        models: ModelLocator
    ): String {
        val wantAdvanced = selected == "advanced"
        return if (wantAdvanced && capability.supportsAdvanced() && models.hasNllbModel()) "nllb" else "mlkit"
    }

    private fun resolveActive(selected: String, capability: DeviceCapability, models: ModelLocator): Pair<String, String?> {
        if (selected != "advanced") return "standard" to null
        if (!capability.supportsAdvanced()) return "standard" to "device not capable"
        if (!models.hasNllbModel()) return "standard" to "model missing"
        return "advanced" to null
    }

    private fun readEnginePrefSync(prefs: DataStore<Preferences>): String? = runBlocking {
        try {
            val first = prefs.data.firstOrNull()
            first?.get(KEY_ENGINE)
        } catch (t: Throwable) { null }
    }
}
