package com.laststop.app

import java.util.Locale

/** One local alert snapshot. No raw coordinates, account data or location history. */
object JourneyAlertReport {
    fun format(state: JourneyState, nowMs: Long, recordedAt: String, version: String): String {
        val age = state.lastFixMs?.let { ((nowMs - it).coerceAtLeast(0) / 1000).toString() + " s" } ?: "No confirmed fix"
        val accuracy = state.accuracy?.let { String.format(Locale.ROOT, "%.0f m", it) } ?: "Unknown"
        val progress = state.trustedProgress?.let { String.format(Locale.ROOT, "%.0f m", it) } ?: "Unknown"
        return """
            wakemeup $version
            Recorded: $recordedAt
            Alert type: ${state.alarmKind ?: "Unknown"}
            Alarm output: ${if (state.isArrivalAlarm) "Station alarm with sound and vibration" else "Silent warning; no sound or vibration"}
            Journey: ${state.plan.source.name} → ${state.plan.destination.name}
            Current leg: ${state.leg.route.stations.first().name} → ${state.destination.name}
            Planned wake-up station: ${state.leg.wakeStation.name}
            Reason: ${state.alarmReason ?: state.message}
            Last confirmed fix age: $age
            Last confirmed accuracy: $accuracy
            Confirmed progress along this leg: $progress
            Last location issue: ${state.lastLocationIssue ?: "None recorded"}
        """.trimIndent()
    }
}
