package com.globespeak.mobile.ui.languages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.globespeak.mobile.data.Settings
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
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
    private val manager = RemoteModelManager.getInstance()

    val targetLanguage: StateFlow<String> = settings.targetLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fr")

    private val _modelState = MutableStateFlow<ModelState>(ModelState.Checking)
    val modelState: StateFlow<ModelState> = _modelState

    val languages: List<String> = TranslateLanguage.getAllLanguages().sorted()

    init {
        viewModelScope.launch { refreshModelStatus() }
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
                val downloaded = manager.getDownloadedModels(TranslateRemoteModel::class.java).await()
                val exists = downloaded.any { it.language == tgt }
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
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(tgt)
                    .build()
                val translator = Translation.getClient(options)
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()
                translator.close()
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
                val model = TranslateRemoteModel.Builder(tgt).build()
                manager.deleteDownloadedModel(model).await()
                LogBus.log("Languages", "Model deleted: $tgt", LogLine.Kind.ENGINE)
                refreshModelStatus()
            } catch (t: Throwable) {
                _modelState.value = ModelState.Error(t.message ?: "Delete failed")
            }
        }
    }
}
