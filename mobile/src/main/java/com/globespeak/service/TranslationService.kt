package com.globespeak.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.asr.NoOpStreamingAsr
import com.globespeak.engine.asr.StreamingAsr
import com.globespeak.engine.backend.BackendFactory
import com.globespeak.engine.whisper.WhisperStreamingSession
import com.globespeak.engine.proto.EngineState
import com.globespeak.engine.proto.EngineStatus
import com.globespeak.shared.Bridge
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.DataInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.globespeak.mobile.data.Settings
import com.globespeak.mobile.data.appDataStore
import org.json.JSONObject

class TranslationService : WearableListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val dependencies by lazy { dependenciesFactory?.invoke(this) ?: createDefaultDependencies() }
    private val settings by lazy { Settings(this) }

    private val translator get() = dependencies.translator
    private val streamingAsrFactory get() = dependencies.streamingAsrFactory
    private val messageClient get() = dependencies.messageClient
    private val channelClient get() = dependencies.channelClient
    private val backendInfo get() = dependencies.backendInfo

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // Enter foreground immediately to satisfy startForegroundService() deadline
        startForeground(NOTIF_ID, buildNotification("Idle – waiting for audio"))
        _running.tryEmit(true)
        LogBus.log(TAG, "Service created", LogLine.Kind.DATALAYER)
        val info = backendInfo
        LogBus.log(
            TAG,
            "Backend selected=${info.selected} active=${info.active} reason=${info.reason ?: "none"}",
            LogLine.Kind.ENGINE
        )
        val fallbackReason = whisperFallbackReason(info.reason)
        if (info.selected == "advanced" && info.active != "advanced") {
            updateEngineState(
                status = EngineStatus.WhisperUnavailable,
                statusReason = StatusReasonUpdate.Set(fallbackReason)
            )
        } else {
            updateEngineState(
                status = EngineStatus.Ready,
                statusReason = StatusReasonUpdate.Set(null)
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _running.tryEmit(false)
        LogBus.log(TAG, "Service destroyed", LogLine.Kind.DATALAYER)
        updateEngineState(
            connected = false,
            status = EngineStatus.Unknown,
            statusReason = StatusReasonUpdate.Set(null)
        )
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
                channelClient.getInputStream(channel).addOnSuccessListener { inputStream ->
                    serviceScope.launch {
                        inputStream.use { inp ->
                            val dis = DataInputStream(inp)
                            val asr = streamingAsrFactory(this@TranslationService)
                            val nodeId = channel.nodeId
                            val processor = TranslationStreamProcessor(
                                translator = translator,
                                getTargetLanguage = { settings.targetLanguage.first() },
                                sendText = { type, text, seq ->
                                    sendTextPacket(messageClient, nodeId, type, text, seq)
                                }
                            )
                            val frames = processor.process(dis, asr)
                            Log.i(TAG, "Processed frames=$frames")
                        }
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get InputStream for channel", e)
                    LogBus.log(TAG, "Input stream failure: ${e.message}", LogLine.Kind.DATALAYER)
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
                val now = System.currentTimeMillis()
                updateEngineState(
                    connected = true,
                    heartbeatAt = now
                )
            }
            Bridge.PATH_CONTROL_HEARTBEAT -> {
                // Echo back timestamp for RTT measurement
                Wearable.getMessageClient(this)
                    .sendMessage(event.sourceNodeId, Bridge.PATH_CONTROL_HEARTBEAT, event.data ?: ByteArray(0))
                val now = System.currentTimeMillis()
                updateEngineState(connected = true, heartbeatAt = now)
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

    private fun createDefaultDependencies(): Dependencies {
        val (backend, info) = BackendFactory.buildWithInfo(this, this.appDataStore)
        return Dependencies(
            translator = TranslatorEngine(backend),
            streamingAsrFactory = { ctx -> defaultStreamingAsr(ctx) },
            messageClient = Wearable.getMessageClient(this),
            channelClient = Wearable.getChannelClient(this),
            backendInfo = info
        )
    }

    private fun defaultStreamingAsr(context: Context): StreamingAsr {
        return runCatching { WhisperStreamingSession(context) }
            .onSuccess {
                updateEngineState(
                    status = EngineStatus.Ready,
                    statusReason = StatusReasonUpdate.Set(null)
                )
            }
            .onFailure { t ->
                val reason = whisperFallbackReason(t.message)
                LogBus.log(TAG, "Whisper unavailable: ${t.message}", LogLine.Kind.ENGINE)
                updateEngineState(
                    status = EngineStatus.WhisperUnavailable,
                    statusReason = StatusReasonUpdate.Set(reason)
                )
            }
            .getOrElse { NoOpStreamingAsr() }
    }

    private fun whisperFallbackReason(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun updateEngineState(
        connected: Boolean? = null,
        heartbeatAt: Long? = null,
        status: EngineStatus? = null,
        statusReason: StatusReasonUpdate = StatusReasonUpdate.Unchanged,
        lastError: ErrorUpdate = ErrorUpdate.Unchanged
    ) {
        var state = _engineState.value
        if (connected != null) state = state.copy(connected = connected)
        if (heartbeatAt != null) state = state.copy(lastHeartbeatAt = heartbeatAt)
        if (status != null) state = state.copy(status = status)
        when (statusReason) {
            StatusReasonUpdate.Unchanged -> Unit
            is StatusReasonUpdate.Set -> state = state.copy(statusReason = statusReason.value)
        }
        when (lastError) {
            ErrorUpdate.Unchanged -> Unit
            is ErrorUpdate.Set -> state = state.copy(lastError = lastError.value)
        }
        _engineState.value = state
        pushEngineStateToWear(state)
    }

    private fun pushEngineStateToWear(state: EngineState) {
        try {
            val data = PutDataMapRequest.create(Bridge.PATH_ENGINE_STATE)
            val map = data.dataMap
            map.putBoolean("connected", state.connected)
            map.putLong("lastHeartbeatAt", state.lastHeartbeatAt)
            map.putString("status", state.status.name)
            state.statusReason?.let { map.putString("statusReason", it) } ?: map.remove("statusReason")
            state.lastError?.let { map.putString("lastError", it) } ?: map.remove("lastError")
            state.modelsReady?.let { map.putBoolean("modelsReady", it) }
            Wearable.getDataClient(this).putDataItem(data.setUrgent().asPutDataRequest())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to push engine state", t)
        }
    }

    private sealed interface StatusReasonUpdate {
        object Unchanged : StatusReasonUpdate
        data class Set(val value: String?) : StatusReasonUpdate
    }

    private sealed interface ErrorUpdate {
        object Unchanged : ErrorUpdate
        data class Set(val value: String?) : ErrorUpdate
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
        private val initialEngineState = EngineState(connected = false, lastHeartbeatAt = 0L)
        private val _engineState = MutableStateFlow(initialEngineState)
        val engineState: StateFlow<EngineState> = _engineState.asStateFlow()
        internal var dependenciesFactory: ((TranslationService) -> Dependencies)? = null
    }

    data class Dependencies(
        val translator: TranslatorEngine,
        val streamingAsrFactory: (Context) -> StreamingAsr,
        val messageClient: MessageClient,
        val channelClient: ChannelClient,
        val backendInfo: BackendFactory.EngineSelectionInfo
    )
}
