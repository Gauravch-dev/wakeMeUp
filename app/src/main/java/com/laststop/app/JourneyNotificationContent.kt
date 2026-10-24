package com.laststop.app

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A notification must never present a rejected or stale position as a live distance. */
data class JourneyNotificationContent(
    val title: String,
    val summary: String,
    val detail: String,
    val chip: String,
    val fraction: Float?,
    val livePosition: Boolean,
    val promote: Boolean,
) {
    companion object {
        fun from(state: JourneyState, nowMs: Long): JourneyNotificationContent {
            val fraction = JourneyVisuals.fraction(state)
            val target = state.destination.name
            val route = state.leg.route
            val legLabel = if (state.hasNextLeg) "Change trains at $target" else "Destination: $target"
            val wake = if (state.leg.wakeAtSource) "Next-station trip · Stay awake"
                else "Wake near ${state.leg.wakeStation.name} · One station before $target"
            val context = "$wake\n${route.stations.first().name} → $target · ${state.leg.lineName}"
            if (state.status == TrackingStatus.ALARM) {
                return JourneyNotificationContent(
                    when (state.alarmKind) {
                        AlarmKind.APPROACH -> "Wake up · $target"
                        AlarmKind.DIRECTION -> "Silent direction warning · $target"
                        else -> "Silent monitoring warning · $target"
                    },
                    state.message, "$legLabel\n${state.message}\n$context", "Check", fraction, false, false,
                )
            }
            val active = state.status in setOf(TrackingStatus.ACQUIRING, TrackingStatus.TRACKING, TrackingStatus.UNCERTAIN)
            if (state.status == TrackingStatus.TRACKING && JourneyVisuals.fresh(state, nowMs) && fraction != null) {
                val progress = state.trustedProgress!!.coerceIn(0.0, route.cumulative.last())
                val remaining = route.cumulative.last() - progress
                // Geographic stations ahead, not a claim about this train's stopping pattern.
                val stations = route.cumulative.drop(1).count { it > progress + 100.0 }
                val ahead = "$stations ${if (stations == 1) "station" else "stations"} ahead"
                val distance = if (remaining < 1000) "~${(ceil(remaining / 50) * 50).toInt()} m"
                    else String.format(Locale.ROOT, "~%.1f km", remaining / 1000)
                val chip = if (remaining < 1000) "~${(ceil(remaining / 50) * 50).toInt()}m"
                    else if (remaining < 100_000) String.format(Locale.ROOT, "~%.1fkm", remaining / 1000)
                    else "~${(remaining / 1000).roundToInt()}km"
                val next = JourneyVisuals.nextStation(state)?.name ?: target
                return JourneyNotificationContent("$target · $distance", "Tracking active · $ahead · Next: $next",
                    "$legLabel\n$distance · $ahead\nNext: $next · Tracking active\n$context",
                    chip, fraction, true, true)
            }
            val acquiring = state.status == TrackingStatus.ACQUIRING && fraction == null
            val status = when {
                !active -> "Journey ended"
                acquiring -> "Finding location · Stay awake"
                else -> "Tracking uncertain · Check your journey"
            }
            val reason = if (state.status == TrackingStatus.TRACKING)
                "Waiting for a fresh, trusted location. Stay awake until tracking recovers." else state.message
            return JourneyNotificationContent(target, status,
                "$legLabel\n$status\n$reason\n$context", if (acquiring) "GPS…" else "Check",
                fraction, false, active)
        }
    }
}
