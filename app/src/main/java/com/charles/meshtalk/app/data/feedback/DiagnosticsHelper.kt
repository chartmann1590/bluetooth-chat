package com.charles.meshtalk.app.data.feedback

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticsHelper {

    fun collectDiagnostics(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val appName = context.applicationInfo.loadLabel(context.packageManager).toString()
        val versionName = info.versionName ?: "unknown"
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()

        val statFs = StatFs(Environment.getDataDirectory().absolutePath)
        val freeStorage = statFs.availableBytes / (1024 * 1024 * 1024)
        val totalStorage = statFs.totalBytes / (1024 * 1024 * 1024)

        val runtime = Runtime.getRuntime()
        val freeMemory = (runtime.freeMemory() + (runtime.maxMemory() - runtime.totalMemory())) / (1024 * 1024)
        val totalMemory = runtime.maxMemory() / (1024 * 1024)

        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
        timeFormat.timeZone = TimeZone.getDefault()

        return buildString {
            appendLine("## Diagnostics")
            appendLine()
            appendLine("- App: $appName")
            appendLine("- Package: ${context.packageName}")
            appendLine("- Version: $versionName ($versionCode)")
            appendLine("- Device: ${Build.MODEL}")
            appendLine("- Brand: ${Build.BRAND}")
            appendLine("- Manufacturer: ${Build.MANUFACTURER}")
            appendLine("- Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("- Locale: ${Locale.getDefault()}")
            appendLine("- Time Zone: ${TimeZone.getDefault().id}")
            appendLine("- Storage Free/Total: $freeStorage GB / $totalStorage GB")
            appendLine("- Memory Free/Total: $freeMemory MB / $totalMemory MB")
            appendLine("- Timestamp: ${timeFormat.format(Date())}")
        }
    }
}
