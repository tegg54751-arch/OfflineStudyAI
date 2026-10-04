package com.offlinestudy.ai

import android.content.Context

object BuildConfigInfo {
    fun versionName(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("1.0")
}
