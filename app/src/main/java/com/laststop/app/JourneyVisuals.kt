package com.laststop.app

/** Read-only presentation of confirmed route progress. Never extrapolates a train position. */
object JourneyVisuals {
    fun fraction(state: JourneyState): Float? {
        val progress = state.trustedProgress?.takeIf { it.isFinite() } ?: return null
        val total = state.leg.route.cumulative.last().takeIf { it.isFinite() && it > 0 } ?: return null
        return (progress / total).coerceIn(0.0, 1.0).toFloat()
    }

    fun fresh(state: JourneyState, nowMs: Long): Boolean {
        val age = state.lastFixMs?.let { nowMs - it } ?: return false
        return age in 0..19_999 && (state.status == TrackingStatus.TRACKING ||
            state.status == TrackingStatus.ALARM && state.alarmKind == AlarmKind.APPROACH)
    }

    fun nextStation(state: JourneyState): Station? {
        val progress = state.trustedProgress ?: return null
        val route = state.leg.route
        val index = route.cumulative.indexOfFirst { it > progress + 100.0 }
        return route.stations[if (index >= 0) index else route.stations.lastIndex]
    }
}
