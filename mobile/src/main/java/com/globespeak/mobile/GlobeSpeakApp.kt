package com.globespeak.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import timber.log.Timber

class GlobeSpeakApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
