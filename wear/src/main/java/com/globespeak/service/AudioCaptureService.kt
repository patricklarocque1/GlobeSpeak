package com.globespeak.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.nio.ByteBuffer

class AudioCaptureService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var recordJob: Job? = null
    private var channel: ChannelClient.Channel? = null
    private var out: OutputStream? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
    }

    private fun startCapture() {
        if (recordJob?.isActive == true) return
        startForeground(NOTIF_ID, buildNotification("Capturing…"))
        recordJob = scope.launch {
            try {
                val node = findPhoneNode() ?: run {
                    Log.w(TAG, "No connected node (phone) found")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                Log.i(TAG, "Opening channel to node ${node.id}")
                val channelClient = Wearable.getChannelClient(this@AudioCaptureService)
                val ch = Tasks.await(channelClient.openChannel(node.id, AUDIO_PATH))
                channel = ch
                out = Tasks.await(channelClient.getOutputStream(ch))

                captureAndStream(out!!)
            } catch (ce: CancellationException) {
                // ignore
            } catch (t: Throwable) {
                Log.e(TAG, "Capture failed", t)
            } finally {
                try { out?.flush() } catch (_: Throwable) {}
                try { out?.close() } catch (_: Throwable) {}
                channel?.let { Wearable.getChannelClient(this@AudioCaptureService).close(it) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun captureAndStream(output: OutputStream) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; aborting capture")
            return
        }
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = (minBuf.coerceAtLeast(4096) / 2) * 2 // even number
        val buffer = ByteArray(bufferSize)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize
        )

        try {
            recorder.startRecording()
            Log.i(TAG, "Recording started (buffer=$bufferSize)")
            while (scope.isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    output.write(buffer, 0, read)
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord read error: $read")
                    break
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Throwable) {}
            recorder.release()
            Log.i(TAG, "Recording stopped")
        }
    }

    private suspend fun findPhoneNode(): Node? {
        val nodes = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
        return nodes.firstOrNull()
    }

    private fun stopCapture() {
        scope.launch {
            recordJob?.cancelAndJoin()
            recordJob = null
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "GlobeSpeak Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("GlobeSpeak")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AudioCaptureService"
        const val ACTION_START = "com.globespeak.action.START_CAPTURE"
        const val ACTION_STOP = "com.globespeak.action.STOP_CAPTURE"
        const val AUDIO_PATH = "/audio"
        private const val NOTIF_CHANNEL_ID = "globespeak.capture"
        private const val NOTIF_ID = 2001
    }
}
