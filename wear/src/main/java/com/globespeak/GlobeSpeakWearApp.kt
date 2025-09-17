package com.globespeak

import android.app.Application
import android.content.pm.ApplicationInfo
import timber.log.Timber

class GlobeSpeakWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val isDebug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
