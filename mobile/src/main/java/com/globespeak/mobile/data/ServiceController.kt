package com.globespeak.mobile.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.globespeak.service.TranslationService

object ServiceController {
    fun start(context: Context) {
        val intent = Intent(context, TranslationService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stop(context: Context) {
        val intent = Intent(context, TranslationService::class.java)
        context.stopService(intent)
    }
}

