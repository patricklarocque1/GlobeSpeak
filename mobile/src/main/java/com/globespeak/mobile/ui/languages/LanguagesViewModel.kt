package com.globespeak.mobile.ui.languages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.mlkit.MLKitTranslationBackend
import com.globespeak.mobile.data.Settings
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.globespeak.shared.Bridge
import androidx.datastore.preferences.core.edit
import com.globespeak.engine.backend.DeviceCapability
import com.globespeak.engine.backend.ModelLocator
import com.globespeak.engine.whisper.WhisperModelLocator
import com.globespeak.mobile.data.appDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ModelState {
    data object Idle : ModelState
    data object Checking : ModelState
    data object Downloading : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

data class LanguagesUi(
    val supported: List<String> = emptyList(),
    val selectedTarget: String = Bridge.DEFAULT_TARGET_LANG,
    val wifiOnly: Boolean = true,
    val modelState: ModelState = ModelState.Checking,
    val engineMode: String = "standard", // "standard" | "advanced"
    val deviceCapable: Boolean = false,
    val nllbModelPresent: Boolean = false,
    val whisperModelPresent: Boolean = false,
    val whisperMissing: List<String> = emptyList(),
    val activeEngine: String = "standard",
    val fallbackReason: String? = null
)

class LanguagesViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val engine = TranslatorEngine(MLKitTranslationBackend())
    private val settings = Settings(ctx)

    private val _ui = MutableStateFlow(LanguagesUi())
    val ui: StateFlow<LanguagesUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val tags = engine.supportedLanguages().toList().sorted()
            _ui.update { it.copy(supported = tags) }

            settings.targetLanguage.collectLatest { lang ->
                _ui.update { it.copy(selectedTarget = lang) }
                checkModel(lang)
            }
        }
        // Capability & model presence (best-effort from engine backend helpers in app layer)
        refreshAdvancedStatus()
        // Read engine mode
        viewModelScope.launch {
            val key = androidx.datastore.preferences.core.stringPreferencesKey(com.globespeak.shared.Bridge.DS_TRANSLATION_ENGINE)
            ctx.appDataStore.data.collectLatest { prefs ->
                _ui.update { it.copy(engineMode = prefs[key] ?: "standard") }
                refreshEngineSelectionInfo()
            }
        }
    }

    fun toggleWifiOnly(v: Boolean) { _ui.update { it.copy(wifiOnly = v) } }

    fun setTarget(lang: String) = viewModelScope.launch {
        settings.setTargetLanguage(lang)
        // collector will re-check model when value changes
    }

    fun setEngineMode(mode: String) = viewModelScope.launch {
        val key = androidx.datastore.preferences.core.stringPreferencesKey(com.globespeak.shared.Bridge.DS_TRANSLATION_ENGINE)
        ctx.appDataStore.edit { it[key] = mode }
        refreshEngineSelectionInfo()
    }

    fun checkModel(lang: String = _ui.value.selectedTarget) = viewModelScope.launch {
        _ui.update { it.copy(modelState = ModelState.Checking) }
        val ready = runCatching { engine.isModelReady(lang) }.getOrDefault(false)
        _ui.update { it.copy(modelState = if (ready) ModelState.Ready else ModelState.Idle) }
    }

    fun downloadModel() = viewModelScope.launch {
        val lang = _ui.value.selectedTarget
        _ui.update { it.copy(modelState = ModelState.Downloading) }
        runCatching { engine.ensureModel(lang, requireWifi = _ui.value.wifiOnly) }
            .onSuccess {
                LogBus.log("Languages", "Model downloaded: $lang", LogLine.Kind.ENGINE)
                _ui.update { it.copy(modelState = ModelState.Ready) }
            }
            .onFailure { e ->
                _ui.update { it.copy(modelState = ModelState.Error(e.message ?: "Download failed")) }
            }
    }

    fun deleteModel() = viewModelScope.launch {
        val lang = _ui.value.selectedTarget
        runCatching { engine.deleteModel(lang) }
        LogBus.log("Languages", "Model deleted: $lang", LogLine.Kind.ENGINE)
        checkModel(lang)
        refreshEngineSelectionInfo()
    }

    fun refreshAdvancedStatus() = viewModelScope.launch { refreshAdvancedStatusInternal() }

    fun onModelFileImported(fileName: String) {
        viewModelScope.launch {
            LogBus.log("Languages", "Imported $fileName", LogLine.Kind.ENGINE)
            refreshAdvancedStatusInternal()
        }
    }

    private suspend fun refreshAdvancedStatusInternal() {
        val cap = DeviceCapability(ctx).supportsAdvanced()
        val nllbLocator = ModelLocator(ctx)
        val whisperLocator = WhisperModelLocator(ctx)
        val nllbPresent = nllbLocator.hasNllbModel()
        val whisperPresent = whisperLocator.hasModel()
        val whisperMissing = whisperLocator.requiredFiles().filterNot { it.exists() }.map { it.name }
        _ui.update {
            it.copy(
                deviceCapable = cap,
                nllbModelPresent = nllbPresent,
                whisperModelPresent = whisperPresent,
                whisperMissing = whisperMissing
            )
        }
        LogBus.log(
            "Languages",
            "Advanced status capable=$cap nllb=$nllbPresent whisper=$whisperPresent missing=${whisperMissing.joinToString()}",
            LogLine.Kind.ENGINE
        )
        refreshEngineSelectionInfo()
    }

    private suspend fun refreshEngineSelectionInfo() {
        val prefs = ctx.appDataStore
        val (backend, info) = com.globespeak.engine.backend.BackendFactory.buildWithInfo(ctx, prefs)
        _ui.update { it.copy(activeEngine = info.active, fallbackReason = info.reason) }
        LogBus.log(
            "Engine",
            "Selection selected=${info.selected} active=${info.active} reason=${info.reason ?: "none"}",
            LogLine.Kind.ENGINE
        )
    }
}
