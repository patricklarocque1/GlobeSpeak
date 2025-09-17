package com.globespeak.engine.backend

import android.app.ActivityManager
import android.content.Context
import android.os.Build

open class DeviceCapability(private val context: Context) {
    open fun supportsAdvanced(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val okRam = !am.isLowRamDevice && am.memoryClass >= 256 // MB heuristic
        val okAbi = Build.SUPPORTED_ABIS.any { it.contains("arm64") || it.contains("x86_64") }
        return okRam && okAbi
    }
}

