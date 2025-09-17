package com.globespeak.mobile

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import com.globespeak.mobile.data.Settings
import com.globespeak.mobile.data.SettingsBridge
import com.globespeak.mobile.logging.LogBus
import com.globespeak.mobile.logging.LogLine
import com.globespeak.shared.Bridge
import com.globespeak.mobile.ui.AppNav
import com.globespeak.mobile.ui.GlobeSpeakTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val requestNotificationsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }
    private var settingsJob: Job? = null
    private val messages by lazy { Wearable.getMessageClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()

        val settings = Settings(applicationContext)
        val targetFlow = settings.targetLanguage.stateIn(lifecycleScope, SharingStarted.Eagerly, Bridge.DEFAULT_TARGET_LANG)
        settingsJob = lifecycleScope.launch {
            SettingsBridge(applicationContext, targetFlow).start()
        }

        messages.addListener(MessageClient.OnMessageReceivedListener { evt ->
            if (evt.path == Bridge.PATH_SETTINGS_REQUEST) {
                lifecycleScope.launch {
                    val current = targetFlow.value
                    runCatching {
                        messages.sendMessage(evt.sourceNodeId, Bridge.PATH_SETTINGS_TARGET_LANG, current.toByteArray())
                    }
                    LogBus.log("MainActivity", "Settings requested by watch; sent $current", LogLine.Kind.DATALAYER)
                }
            }
        })
        setContent {
            GlobeSpeakTheme {
                AppNav()
            }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
