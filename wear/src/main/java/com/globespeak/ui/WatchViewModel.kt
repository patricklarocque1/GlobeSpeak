package com.globespeak.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.Node
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.globespeak.shared.Bridge

enum class WatchStatus { Disconnected, Ready, Listening, Translating }

data class MessageItem(val from: String, val text: String)

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = getApplication<Application>()

    private val _status = MutableStateFlow(WatchStatus.Disconnected)
    val status: StateFlow<WatchStatus> = _status.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageItem>>(emptyList())
    val messages: StateFlow<List<MessageItem>> = _messages.asStateFlow()

    private val _targetLang = MutableStateFlow("—")
    val targetLang: StateFlow<String> = _targetLang.asStateFlow()

    init {
        refreshNodes()
    }

    fun refreshNodes() {
        viewModelScope.launch {
            try {
                val nodes = Wearable.getNodeClient(ctx).connectedNodes.await()
                _status.value = if (nodes.isEmpty()) WatchStatus.Disconnected else if (_capturing.value) WatchStatus.Listening else WatchStatus.Ready
            } catch (e: ApiException) {
                if (e.statusCode == ConnectionResult.API_UNAVAILABLE) {
                    _status.value = if (_capturing.value) WatchStatus.Listening else WatchStatus.Disconnected
                } else {
                    _status.value = WatchStatus.Disconnected
                }
            } catch (_: Throwable) {
                _status.value = WatchStatus.Disconnected
            }
        }
    }

    fun setCapturing(running: Boolean) {
        _capturing.value = running
        refreshNodes()
    }

    fun addPartial(text: String) {
        _status.value = WatchStatus.Translating
        val updated = _messages.value.toMutableList()
        if (updated.isNotEmpty() && updated.last().from == "Partial") {
            updated[updated.lastIndex] = MessageItem(from = "Partial", text = text)
        } else {
            updated.add(MessageItem(from = "Partial", text = text))
        }
        _messages.value = updated.takeLast(100)
    }

    fun addFinal(text: String) {
        val updated = _messages.value.toMutableList()
        // Replace last Partial with final, or append
        if (updated.isNotEmpty() && updated.last().from == "Partial") {
            updated[updated.lastIndex] = MessageItem(from = "GlobeSpeak", text = text)
        } else {
            updated.add(MessageItem(from = "GlobeSpeak", text = text))
        }
        _messages.value = updated.takeLast(100)
        _status.value = if (_capturing.value) WatchStatus.Listening else WatchStatus.Ready
    }

    fun clear() { _messages.value = emptyList() }

    fun setTargetLang(tag: String) { _targetLang.value = tag }
}
