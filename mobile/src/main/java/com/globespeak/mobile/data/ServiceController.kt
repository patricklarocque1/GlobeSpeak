package com.globespeak.mobile.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.globespeak.service.TranslationService

object ServiceController {
    fun start(context: Context) {
        val intent = Intent(context, TranslationService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            // Android 14+: ForegroundServiceStartNotAllowedException or similar
            // Best-effort degrade; caller can retry when app is in foreground
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, TranslationService::class.java)
        context.stopService(intent)
    }
}
