package com.globespeak.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.globespeak.engine.TranslatorEngine
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class TranslationService : WearableListenerService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val engine by lazy { TranslatorEngine() }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onChannelOpened(channel: com.google.android.gms.wearable.ChannelClient.Channel) {
        super.onChannelOpened(channel)
        Log.i(TAG, "Channel opened: ${channel.path} from ${channel.nodeId}")
        if (channel.path != AUDIO_PATH) return

        // Keep service in foreground while processing the audio stream
        startForeground(NOTIF_ID, buildNotification("Receiving audio…"))

        serviceScope.launch {
            try {
                val channelClient = Wearable.getChannelClient(this@TranslationService)
                channelClient.getInputStream(channel).addOnSuccessListener { inputStream ->
                    serviceScope.launch {
                        val baos = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        inputStream.use { inp ->
                            while (true) {
                                val n = inp.read(buffer)
                                if (n <= 0) break
                                baos.write(buffer, 0, n)
                                total += n
                                if (total % (64 * 1024) == 0) {
                                    Log.d(TAG, "Read $total bytes…")
                                }
                            }
                        }

                        val pcm = baos.toByteArray()
                        Log.i(TAG, "Finished reading ${pcm.size} bytes. Transcribing…")
                        val transcript = engine.transcribePcm16LeMono16k(pcm)
                        val translated = engine.translate(transcript, source = "auto", target = "en")

                        // Send translation back to the originating node
                        val bytes = translated.encodeToByteArray()
                        Wearable.getMessageClient(this@TranslationService)
                            .sendMessage(channel.nodeId, TRANSLATION_PATH, bytes)
                            .addOnSuccessListener { Log.i(TAG, "Sent translation (${bytes.size} bytes)") }
                            .addOnFailureListener { e -> Log.e(TAG, "Failed to send translation", e) }
                    }
                }.addOnFailureListener { e ->
                    Log.e(TAG, "Failed to get InputStream for channel", e)
                }.addOnCompleteListener {
                    // Stop foreground once async tasks scheduled; actual stream reading occurs above
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling channel", t)
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    override fun onChannelClosed(channel: ChannelClient.Channel, p1: Int, p2: Int) {
        super.onChannelClosed(channel, p1, p2)
        Log.i(TAG, "Channel closed: ${channel.path}")
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
        const val AUDIO_PATH = "/audio"
        const val TRANSLATION_PATH = "/translation"
        private const val NOTIF_CHANNEL_ID = "globespeak.translation"
        private const val NOTIF_ID = 1001
    }
}

