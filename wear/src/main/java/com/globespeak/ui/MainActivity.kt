package com.globespeak.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.wear.compose.material.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.globespeak.service.AudioCaptureService
import com.globespeak.service.AudioCaptureService.Companion.AUDIO_PATH

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {
    private var lastTranslation: String = ""
    private val messageClient by lazy { Wearable.getMessageClient(this) }

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var capturing by remember { mutableStateOf(false) }
            var translation by remember { mutableStateOf("") }

            // Keep a reference to update from callback
            uiSetCapturing = { capturing = it }
            uiSetTranslation = { translation = it }

            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(if (capturing) "Capturing…" else "Idle")
                    Button(onClick = {
                        if (capturing) stopCapture() else ensurePermissionAndStart()
                        capturing = !capturing
                    }) {
                        Text(if (capturing) "Stop" else "Start")
                    }
                    if (translation.isNotEmpty()) {
                        Text(translation)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        messageClient.addListener(this)
    }

    override fun onPause() {
        super.onPause()
        messageClient.removeListener(this)
    }

    private fun ensurePermissionAndStart() {
        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startCapture() {
        val i = Intent(this, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_START }
        startForegroundService(i)
        uiSetCapturing?.invoke(true)
    }

    private fun stopCapture() {
        val i = Intent(this, AudioCaptureService::class.java).apply { action = AudioCaptureService.ACTION_STOP }
        startService(i)
        uiSetCapturing?.invoke(false)
    }

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        if (event.path == "/translation") {
            val text = String(event.data)
            lastTranslation = text
            uiSetTranslation?.invoke(text)
        }
    }

    private var uiSetCapturing: ((Boolean) -> Unit)? = null
    private var uiSetTranslation: ((String) -> Unit)? = null
}
