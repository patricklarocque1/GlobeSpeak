package com.globespeak.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.ScalingLazyListState
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberScalingLazyListState
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.globespeak.engine.proto.EngineStatus
import com.globespeak.service.AudioCaptureService
import com.globespeak.shared.Bridge
import com.globespeak.ui.about.AboutActivity
import org.json.JSONObject

class MainActivity : ComponentActivity(),
    MessageClient.OnMessageReceivedListener,
    com.google.android.gms.wearable.DataClient.OnDataChangedListener {
    private val messageClient by lazy { Wearable.getMessageClient(this) }
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private val vm: WatchViewModel by viewModels()

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCapture() else vm.setCapturing(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val listState = rememberScalingLazyListState()

            MaterialTheme {
                WatchScaffold(vm, listState,
                    onStart = { ensurePermissionAndStart() },
                    onStop = { stopCapture() },
                    onClear = { vm.clear() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        messageClient.addListener(this)
        dataClient.addListener(this)
        vm.refreshNodes()
        // Request settings if unknown
        if (vm.targetLang.value == "—") {
            Wearable.getNodeClient(this).connectedNodes.addOnSuccessListener { nodes ->
                nodes.firstOrNull()?.let { node ->
                    messageClient.sendMessage(node.id, Bridge.PATH_SETTINGS_REQUEST, ByteArray(0))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        messageClient.removeListener(this)
        dataClient.removeListener(this)
    }

    private fun ensurePermissionAndStart() {
        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startCapture() {
        val i = Intent(this, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_START }
        startForegroundService(i)
        vm.setCapturing(true)
    }

    private fun stopCapture() {
        val i = Intent(this, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_STOP }
        startService(i)
        vm.setCapturing(false)
    }

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        when (event.path) {
            Bridge.PATH_TEXT_OUT -> {
                val s = String(event.data)
                try {
                    val json = JSONObject(s)
                    val type = json.optString("type", "final")
                    val text = json.optString("text", "")
                    if (type == "partial") vm.addPartial(text) else vm.addFinal(text)
                } catch (_: Throwable) {
                    vm.addFinal(s)
                }
            }
            Bridge.PATH_SETTINGS_TARGET_LANG -> {
                val tag = event.data?.toString(Charsets.UTF_8) ?: return
                vm.setTargetLang(tag)
            }
            Bridge.PATH_STATUS_ASR -> {
                val payload = event.data?.toString(Charsets.UTF_8) ?: return
                runCatching { JSONObject(payload) }
                    .onSuccess { json ->
                        val status = json.optString("status").takeIf { it.isNotBlank() }
                        val backend = json.optString("backend").takeIf { it.isNotBlank() }
                        val message = if (json.has("message")) json.optString("message").takeIf { it.isNotBlank() } else null
                        vm.updateAsrStatus(status, backend, message)
                    }
            }
        }
    }

    override fun onDataChanged(events: com.google.android.gms.wearable.DataEventBuffer) {
        events.use {
            it.forEach { event ->
                if (event.type != com.google.android.gms.wearable.DataEvent.TYPE_CHANGED) return@forEach
                if (event.dataItem.uri.path != Bridge.PATH_ENGINE_STATE) return@forEach
                val map = com.google.android.gms.wearable.DataMapItem.fromDataItem(event.dataItem).dataMap
                val connected = map.getBoolean("connected", false)
                val statusName = map.getString("status")
                val status = runCatching { EngineStatus.valueOf(statusName ?: EngineStatus.Unknown.name) }
                    .getOrDefault(EngineStatus.Unknown)
                val reason = map.getString("statusReason")
                vm.updateEngineConnection(connected)
                vm.updateEngineStatus(status, reason)
            }
        }
    }
}

@Composable
private fun WatchScaffold(
    vm: WatchViewModel,
    listState: ScalingLazyListState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit
) {
    val status by vm.status.collectAsState()
    val capturing by vm.capturing.collectAsState()
    val items by vm.messages.collectAsState()
    val targetLang by vm.targetLang.collectAsState()
    val asrBanner by vm.asrBanner.collectAsState()

    Scaffold(
        timeText = {},
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            val statusText = asrBanner ?: statusLabel(status)
            item { Text(statusText) }
            item { Text("Target: ${displayLang(targetLang)}") }
            item {
                val isCapturing = capturing
                Button(onClick = { if (isCapturing) onStop() else onStart() }) {
                    Text(if (isCapturing) "Stop" else "Start")
                }
            }
            items(items.size) { idx ->
                val m = items[idx]
                Chip(onClick = {}, label = { Text("${m.from}: ${m.text}") })
            }
            item { Chip(onClick = onClear, label = { Text("Clear") }) }
            item {
                val ctx = LocalContext.current
                Chip(
                    onClick = { ctx.startActivity(Intent(ctx, AboutActivity::class.java)) },
                    label = { Text("About & Licenses") }
                )
            }
        }
    }
}

private fun statusLabel(s: WatchStatus) = when (s) {
    WatchStatus.Disconnected -> "Disconnected"
    WatchStatus.Ready -> "Ready"
    WatchStatus.Listening -> "Listening"
    WatchStatus.Translating -> "Translating"
}

private fun displayLang(tag: String): String = try {
    if (tag == "—") tag else java.util.Locale(tag).displayLanguage
} catch (_: Throwable) { tag }
