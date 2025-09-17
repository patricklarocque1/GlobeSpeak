package com.globespeak.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.backend.BackendFactory
import com.globespeak.engine.proto.EngineState
import com.globespeak.shared.Bridge
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.globespeak.mobile.data.Settings
import com.globespeak.mobile.data.appDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

class TranslationService : WearableListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val engine by lazy { TranslatorEngine(BackendFactory.build(this, this.appDataStore)) }
    private val settings by lazy { Settings(this) }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // Enter foreground immediately to satisfy startForegroundService() deadline
        startForeground(NOTIF_ID, buildNotification("Idle – waiting for audio"))
        _running.tryEmit(true)
        LogBus.log(TAG, "Service created", LogLine.Kind.DATALAYER)
    }

    override fun onDestroy() {
        super.onDestroy()
        _running.tryEmit(false)
        LogBus.log(TAG, "Service destroyed", LogLine.Kind.DATALAYER)
    }

    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        super.onChannelOpened(channel)
        Log.i(TAG, "Channel opened: ${channel.path} from ${channel.nodeId}")
        LogBus.log(TAG, "Channel opened ${channel.path} from ${channel.nodeId}", LogLine.Kind.DATALAYER)
        if (channel.path != Bridge.PATH_AUDIO_PCM16) return

        // Keep service in foreground while processing the audio stream
        startForeground(NOTIF_ID, buildNotification("Receiving audio…"))

        serviceScope.launch {
            try {
                val channelClient = Wearable.getChannelClient(this@TranslationService)
                channelClient.getInputStream(channel).addOnSuccessListener { inputStream ->
                    serviceScope.launch {
                        inputStream.use { inp ->
                            val dis = DataInputStream(inp)
                            var totalFrames = 0
                            val asr = StreamingAsr()
                            val nodeId = channel.nodeId
                            val mc = Wearable.getMessageClient(this@TranslationService)
                            while (true) {
                                // Read header: seq(int), ts(long), size(int) little endian
                                val header = ByteArray(16)
                                val hRead = dis.read(header)
                                if (hRead <= 0) break
                                if (hRead < header.size) break
                                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                                val seq = bb.int
                                val ts = bb.long
                                val size = bb.int
                                if (size <= 0 || size > 256_000) break
                                val payload = ByteArray(size)
                                dis.readFully(payload)
                                totalFrames++

                                val partial = asr.onPcmChunk(payload)
                                if (partial != null) {
                                    // Translate partial
                                    val tgt = try { settings.targetLanguage.first() } catch (_: Throwable) { "en" }
                                    try { engine.ensureModel(tgt) } catch (_: Throwable) {}
                                    val t = engine.translate(partial, source = "auto", target = tgt)
                                    sendTextPacket(mc, nodeId, type = "partial", text = t, seq = seq)
                                }
                            }
                            // Finalize
                            val finalText = asr.finalizeText()
                            if (finalText.isNotEmpty()) {
                                val tgt = try { settings.targetLanguage.first() } catch (_: Throwable) { "en" }
                                try { engine.ensureModel(tgt) } catch (_: Throwable) {}
                                val t = engine.translate(finalText, source = "auto", target = tgt)
                                sendTextPacket(Wearable.getMessageClient(this@TranslationService), channel.nodeId, type = "final", text = t, seq = asr.seq)
                            }
                            Log.i(TAG, "Processed frames=$totalFrames")
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get InputStream for channel", e)
                }.addOnCompleteListener {
                    // Stop foreground once async tasks scheduled; actual stream reading occurs above
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling channel", t)
                LogBus.log(TAG, "Error: ${t.message}", LogLine.Kind.DATALAYER)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    override fun onChannelClosed(channel: ChannelClient.Channel, p1: Int, p2: Int) {
        super.onChannelClosed(channel, p1, p2)
        Log.i(TAG, "Channel closed: ${channel.path}")
    }

    override fun onMessageReceived(event: com.google.android.gms.wearable.MessageEvent) {
        when (event.path) {
            Bridge.PATH_CONTROL_HANDSHAKE -> {
                // Ack handshake
                Wearable.getMessageClient(this)
                    .sendMessage(event.sourceNodeId, Bridge.PATH_CONTROL_HANDSHAKE, ByteArray(0))
                publishState(connected = true)
            }
            Bridge.PATH_CONTROL_HEARTBEAT -> {
                // Echo back timestamp for RTT measurement
                Wearable.getMessageClient(this)
                    .sendMessage(event.sourceNodeId, Bridge.PATH_CONTROL_HEARTBEAT, event.data ?: ByteArray(0))
                lastHeartbeatAt = System.currentTimeMillis()
                publishState(connected = true)
            }
        }
    }

    private fun sendTextPacket(mc: MessageClient, nodeId: String, type: String, text: String, seq: Int) {
        val json = JSONObject()
            .put("type", type)
            .put("text", text)
            .put("seq", seq)
        val bytes = json.toString().encodeToByteArray()
        mc.sendMessage(nodeId, Bridge.PATH_TEXT_OUT, bytes)
    }

    private fun publishState(connected: Boolean, error: String? = null) {
        try {
            val state = EngineState(connected, lastHeartbeatAt, error)
            val dataClient = Wearable.getDataClient(this)
            val data = com.google.android.gms.wearable.PutDataMapRequest.create(Bridge.PATH_ENGINE_STATE)
            data.dataMap.putBoolean("connected", state.connected)
            data.dataMap.putLong("lastHeartbeatAt", state.lastHeartbeatAt)
            state.lastError?.let { data.dataMap.putString("lastError", it) }
            dataClient.putDataItem(data.asPutDataRequest())
        } catch (_: Throwable) {}
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "GlobeSpeak Translation",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("GlobeSpeak")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "TranslationService"
        private const val NOTIF_CHANNEL_ID = "globespeak.translation"
        private const val NOTIF_ID = 1001
        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()
        private var lastHeartbeatAt: Long = 0L
    }
}

/**
 * Minimal streaming ASR stub that emits a partial every ~20KB and a final at end.
 */
private class StreamingAsr {
    private val buffer = java.io.ByteArrayOutputStream()
    private var bytesSinceLastPartial = 0
    var seq: Int = 0

    fun onPcmChunk(pcm: ByteArray): String? {
        buffer.write(pcm)
        bytesSinceLastPartial += pcm.size
        return if (bytesSinceLastPartial >= 20_000) {
            bytesSinceLastPartial = 0
            "TRANSCRIPT(len=${buffer.size()})"
        } else null
    }

    fun finalizeText(): String {
        return "TRANSCRIPT(len=${buffer.size()})"
    }
}
