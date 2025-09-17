package com.globespeak.mobile.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.mlkit.MLKitTranslationBackend
import com.globespeak.mobile.data.NodeRepository
import com.globespeak.mobile.data.ServiceController
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.globespeak.service.TranslationService
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val nodeRepo = NodeRepository(ctx)
    private val engine = TranslatorEngine(MLKitTranslationBackend())

    val serviceRunning: StateFlow<Boolean> = TranslationService.running
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val nodes: StateFlow<List<Node>> = nodeRepo.connectedNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _target = MutableStateFlow("fr")
    val target: StateFlow<String> = _target

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input

    private val _result = MutableStateFlow("")
    val result: StateFlow<String> = _result

    private val _languages = MutableStateFlow<List<String>>(emptyList())
    val languages: StateFlow<List<String>> = _languages

    init {
        viewModelScope.launch {
            _languages.value = engine.supportedLanguages().toList().sorted()
        }
    }

    fun setInput(text: String) { _input.value = text }
    fun setTarget(code: String) { _target.value = code }

    fun startService() {
        ServiceController.start(ctx)
        LogBus.log("Dashboard", "Requested service start", LogLine.Kind.DATALAYER)
    }
    fun stopService() {
        ServiceController.stop(ctx)
        LogBus.log("Dashboard", "Requested service stop", LogLine.Kind.DATALAYER)
    }

    fun runQuickTranslate() {
        val text = _input.value
        if (text.isBlank()) return
        val tgt = _target.value
        viewModelScope.launch {
            // Ensure target model, then auto-detect source and translate
            try { engine.ensureModel(tgt) } catch (_: Throwable) {}
            val out = engine.translate(text, source = "auto", target = tgt)
            _result.value = out
            LogBus.log("Dashboard", "Translated -> $out", LogLine.Kind.ENGINE)

            // Best-effort: send translation to first connected node
            try {
                val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                nodes.firstOrNull()?.let { node ->
                    Wearable.getMessageClient(ctx)
                        .sendMessage(node.id, com.globespeak.service.TranslationService.TRANSLATION_PATH, out.encodeToByteArray())
                }
            } catch (_: Throwable) {}
        }
    }
}
