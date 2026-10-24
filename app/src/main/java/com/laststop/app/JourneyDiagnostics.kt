package com.laststop.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.edit
import java.text.DateFormat
import java.util.Date

data class JourneyProcessExit(val timestamp: Long, val reason: Int, val description: String) {
    val userStopped get() = Build.VERSION.SDK_INT >= 30 &&
        (reason == ApplicationExitInfo.REASON_USER_REQUESTED || reason == ApplicationExitInfo.REASON_USER_STOPPED)
}

/** Local diagnostics only. Android may not report every manufacturer-initiated shutdown. */
object JourneyDiagnostics {
    fun latestExit(context: Context, saved: SavedJourney?): JourneyProcessExit? {
        if (Build.VERSION.SDK_INT < 30 || saved == null) return null
        return runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, saved.processId, 5)
                .firstOrNull { it.timestamp >= saved.processStartedWallMs && it.processName == context.packageName }
                ?.let { JourneyProcessExit(it.timestamp, it.reason, it.description.orEmpty().take(300)) }
        }.getOrNull()
    }

    fun record(context: Context, saved: SavedJourney?, event: String,
        exit: JourneyProcessExit? = latestExit(context, saved), error: Exception? = null): String = runCatching {
        val power = context.getSystemService(PowerManager::class.java)
        val manager = context.getSystemService(ActivityManager::class.java)
        val format = DateFormat.getDateTimeInstance()
        val report = buildString {
            appendLine("wakemeup · Monitoring details")
            appendLine("Version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")
            appendLine("Recorded: ${format.format(Date())}")
            appendLine("Event: $event")
            saved?.let {
                val plan = it.plan()
                appendLine("Route: ${plan?.source?.name ?: it.sourceId} → ${plan?.destination?.name ?: it.destinationId}")
                if (plan != null) appendLine("Selected route: ${plan.description}")
                appendLine("Leg: ${it.legIndex + 1} · Restarts: ${it.restartCount}")
                appendLine("Journey started: ${format.format(Date(it.startedWallMs))}")
            }
            if (exit != null) {
                appendLine("Previous process ended: ${format.format(Date(exit.timestamp))}")
                appendLine("Android exit reason: ${exitLabel(exit.reason)} (${exit.reason})")
                if (exit.description.isNotBlank()) appendLine("Android detail: ${exit.description}")
            } else appendLine("Android exit reason: unavailable; the cause is not confirmed.")
            if (error != null) appendLine("Service error: ${error.javaClass.simpleName}: ${error.message.orEmpty().take(300)}")
            appendLine("Android battery optimization exemption: ${power.isIgnoringBatteryOptimizations(context.packageName)}")
            appendLine("Battery Saver: ${power.isPowerSaveMode}")
            if (Build.VERSION.SDK_INT >= 28) appendLine("Android background restriction: ${manager.isBackgroundRestricted}")
            append("Manufacturer-specific battery restrictions may not be visible to Android's standard APIs.")
        }
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit { putString("last_monitoring_report", report) }
        JourneySession.publishMonitoringReport(report)
        report
    }.getOrElse {
        // Diagnostic failures must not interrupt the monitoring they describe.
        "Monitoring details could not be recorded."
    }

    private fun exitLabel(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "App crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
        ApplicationExitInfo.REASON_ANR -> "App not responding"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "System memory pressure"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Excessive resource usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "User-requested stop or manufacturer action reported as a user stop"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Android user/profile stopped"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Permission changed"
        ApplicationExitInfo.REASON_SIGNALED -> "Process terminated by signal"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Process initialization failed"
        ApplicationExitInfo.REASON_OTHER -> "Other system termination"
        else -> "Other or unknown termination"
    }
}
