package com.globespeak.mobile.ui.languages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.globespeak.mobile.data.Settings
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.mlkit.MLKitTranslationBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed class ModelState {
    data object Checking : ModelState()
    data object Downloaded : ModelState()
    data object NotDownloaded : ModelState()
    data class Error(val message: String) : ModelState()
}

class LanguagesViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val settings = Settings(ctx)
    private val engine = TranslatorEngine(MLKitTranslationBackend())

    val targetLanguage: StateFlow<String> = settings.targetLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fr")

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Checking)
    val modelState: StateFlow<ModelState> = _modelState

    val languages: List<String> = run {
        // Resolve synchronously best-effort; will be empty initially if something fails
        try {
            // Note: This is called on init class load; keep it simple.
            // UI does not rely on it being complete here.
            emptyList()
        } catch (_: Throwable) { emptyList() }
    }
    private val _languagesState = MutableStateFlow<List<String>>(emptyList())
    val languagesState: StateFlow<List<String>> = _languagesState

    init {
        viewModelScope.launch {
            _languagesState.value = engine.supportedLanguages().toList().sorted()
            refreshModelStatus()
        }
    }

    fun setTargetLanguage(code: String) {
        viewModelScope.launch {
            settings.setTargetLanguage(code)
            refreshModelStatus()
        }
    }

    fun refreshModelStatus() {
        viewModelScope.launch {
            _modelState.value = ModelState.Checking
            val tgt = targetLanguage.first()
            try {
                val exists = engine.isModelReady(tgt)
                _modelState.value = if (exists) ModelState.Downloaded else ModelState.NotDownloaded
            } catch (t: Throwable) {
                _modelState.value = ModelState.Error(t.message ?: "Unknown error")
            }
        }
    }

    fun downloadModel() {
        viewModelScope.launch {
            _modelState.value = ModelState.Checking
            val tgt = targetLanguage.first()
            try {
                engine.ensureModel(tgt)
                LogBus.log("Languages", "Model downloaded: $tgt", LogLine.Kind.ENGINE)
                refreshModelStatus()
            } catch (t: Throwable) {
                _modelState.value = ModelState.Error(t.message ?: "Download failed")
            }
        }
    }

    fun deleteModel() {
        viewModelScope.launch {
            val tgt = targetLanguage.first()
            try {
                engine.deleteModel(tgt)
                LogBus.log("Languages", "Model deleted: $tgt", LogLine.Kind.ENGINE)
                refreshModelStatus()
            } catch (t: Throwable) {
                _modelState.value = ModelState.Error(t.message ?: "Delete failed")
            }
        }
    }
}
