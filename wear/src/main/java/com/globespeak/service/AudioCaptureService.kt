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
import com.globespeak.engine.proto.AudioFramer
import com.globespeak.audio.VadGate
import com.globespeak.shared.Bridge
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
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
    private var heartbeatJob: Job? = null

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
                val ch = Tasks.await(channelClient.openChannel(node.id, Bridge.PATH_AUDIO_PCM16))
                channel = ch
                out = Tasks.await(channelClient.getOutputStream(ch))

                // Send handshake
                Wearable.getMessageClient(this@AudioCaptureService)
                    .sendMessage(node.id, Bridge.PATH_CONTROL_HANDSHAKE, ByteArray(0))

                // Start heartbeat
                heartbeatJob = scope.launch { heartbeatLoop(node.id) }

                captureAndStream(out!!, node.id)
            } catch (ce: CancellationException) {
                // ignore
            } catch (t: Throwable) {
                Log.e(TAG, "Capture failed", t)
            } finally {
                try { out?.flush() } catch (_: Throwable) {}
                try { out?.close() } catch (_: Throwable) {}
                channel?.let { Wearable.getChannelClient(this@AudioCaptureService).close(it) }
                heartbeatJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private suspend fun captureAndStream(output: OutputStream, nodeId: String) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; aborting capture")
            return
        }
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = (minBuf.coerceAtLeast(8192) / 2) * 2 // even number, >= 8k
        val buffer = ByteArray(bufferSize)

        val vad = VadGate(sampleRate = sampleRate)
        var lastVad = vad.isActive()
        var seq = 1
        val chunkBytesTarget = 10_240 // ~320ms at 16kHz * 2 bytes
        val chunkBuf = ByteArray(chunkBytesTarget)
        var chunkFill = 0

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
                    // VAD gating
                    // Interpret bytes as PCM16 shorts for VAD
                    val asShorts = ShortArray(read / 2)
                    for (i in 0 until asShorts.size) {
                        val lo = buffer[i * 2].toInt() and 0xFF
                        val hi = buffer[i * 2 + 1].toInt()
                        asShorts[i] = ((hi shl 8) or lo).toShort()
                    }
                    val speaking = vad.feed(asShorts)
                    if (speaking != lastVad) {
                        Log.i(TAG, "VAD ${if (speaking) "open" else "closed"} at seq=$seq")
                        lastVad = speaking
                    }
                    if (speaking) {
                        var off = 0
                        while (off < read) {
                            val toCopy = minOf(chunkBytesTarget - chunkFill, read - off)
                            System.arraycopy(buffer, off, chunkBuf, chunkFill, toCopy)
                            chunkFill += toCopy
                            off += toCopy
                            if (chunkFill == chunkBytesTarget) {
                                val framed = AudioFramer.frame(seq++, System.currentTimeMillis(), chunkBuf)
                                output.write(framed)
                                chunkFill = 0
                            }
                        }
                    } else {
                        // If trailing silence and we have a partial chunk, flush it
                        if (chunkFill > 0 && chunkFill >= 2048) {
                            val payload = ByteArray(chunkFill)
                            System.arraycopy(chunkBuf, 0, payload, 0, chunkFill)
                            val framed = AudioFramer.frame(seq++, System.currentTimeMillis(), payload)
                            output.write(framed)
                            Log.d(TAG, "Flushed trailing chunk bytes=$chunkFill seq=$seq")
                            chunkFill = 0
                        }
                    }
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

    private suspend fun heartbeatLoop(nodeId: String) {
        val mc = Wearable.getMessageClient(this)
        while (scope.isActive) {
            try {
                mc.sendMessage(nodeId, Bridge.PATH_CONTROL_HEARTBEAT, System.currentTimeMillis().toString().toByteArray())
            } catch (_: Throwable) { /* ignore */ }
            kotlinx.coroutines.delay(5_000)
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
        private const val NOTIF_CHANNEL_ID = "globespeak.capture"
        private const val NOTIF_ID = 2001
    }
}
