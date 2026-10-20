package com.laststop.app

enum class TrackingStatus { IDLE, ACQUIRING, TRACKING, UNCERTAIN, ALARM, ENDED }
enum class AlarmKind { APPROACH, TRACKING, DIRECTION }
data class JourneyState(
    val status: TrackingStatus = TrackingStatus.IDLE,
    val plan: JourneyPlan = MumbaiNetwork.defaultPlan,
    val legIndex: Int = 0,
    val demo: Boolean = false,
    val message: String = "Choose your stop. We’ll keep watch.",
    val remainingMeters: Double? = null,
    val progress: Double? = null,
    val trustedProgress: Double? = null,
    val accuracy: Double? = null,
    val lastFixMs: Long? = null,
    val alarmReason: String? = null,
    val alarmKind: AlarmKind? = null,
) {
    val leg get() = plan.legs[legIndex]
    val destination get() = leg.route.stations.last()
    val hasNextLeg get() = legIndex < plan.legs.lastIndex
}

/** Pure journey rules; monotonic timestamps avoid changes to the phone clock. */
class JourneyEngine(val plan: JourneyPlan, val legIndex: Int, val demo: Boolean, val startedMs: Long) {
    init {
        require(legIndex in plan.legs.indices)
    }
    val route = plan.legs[legIndex].route
    var state = JourneyState(status = TrackingStatus.ACQUIRING, plan = plan, legIndex = legIndex,
        demo = demo,
        message = "Finding your location. Stay awake until tracking is confirmed.")
        private set
    private var previous: Fix? = null
    private var lastGoodMs: Long? = null
    private var lastProgress: Double? = null
    private var uncertainSince: Long? = null
    private var backwardsSince: Long? = null
    private var furthestProgress: Double? = null

    fun accept(fix: Fix, nowMs: Long): JourneyState {
        if (state.status == TrackingStatus.ALARM) return state
        if (!fix.lat.isFinite() || !fix.lon.isFinite() || fix.lat !in -90.0..90.0 ||
            fix.lon !in -180.0..180.0 || !fix.accuracy.isFinite() || fix.accuracy < 0.0 ||
            fix.accuracy > 150.0 || nowMs - fix.elapsedMs !in 0..20_000 ||
            (previous != null && fix.elapsedMs <= previous!!.elapsedMs)) {
            return uncertain("Waiting for a fresh, accurate location.", nowMs)
        }
        val projection = route.project(fix.lat, fix.lon)
        if (projection.offset > 800.0 + fix.accuracy) {
            return uncertain("Location is away from this route. Check your train and journey.", nowMs)
        }
        previous?.let { old ->
            val seconds = (fix.elapsedMs - old.elapsedMs) / 1000.0
            val travelled = RailwayRoute.distance(old.lat, old.lon, fix.lat, fix.lon)
            if (!demo && travelled > seconds * 55.0 + old.accuracy + fix.accuracy + 100.0) {
                return uncertain("Location jumped unexpectedly. Checking the next reading.", nowMs)
            }
        }
        val target = route.stations.last()
        val remaining = route.cumulative.last() - projection.progress
        state = state.copy(remainingMeters = remaining.coerceAtLeast(0.0), progress = projection.progress,
            accuracy = fix.accuracy, lastFixMs = fix.elapsedMs)
        if (projection.progress + fix.accuracy >= state.leg.wakeProgressMeters) {
            state = state.copy(trustedProgress = projection.progress)
            val wake = state.leg.wakeStation
            val position = when {
                state.leg.wakeAtSource -> "${target.name} is the next station on your route. Stay awake."
                projection.progress > route.cumulative[route.stations.lastIndex - 1] + 100 ->
                    "You’ve reached or passed ${wake.name}. Wake up for ${target.name}."
                else -> "Approaching ${wake.name}. ${target.name} is next on your route."
            }
            return alarm(position + if (state.hasNextLeg) " Wake up to change trains at ${target.name}."
                else if (!state.leg.wakeAtSource) " Time to wake up." else "", AlarmKind.APPROACH)
        }
        val furthest = furthestProgress
        if (!demo && furthest != null && projection.progress < furthest - 500.0) {
            if (backwardsSince == null) backwardsSince = nowMs
            if (nowMs - backwardsSince!! >= 20_000) return alarm("You appear to be travelling away from your destination. Check your direction.", AlarmKind.DIRECTION)
            return uncertain("Checking direction: movement appears to be away from your stop.", nowMs)
        }
        backwardsSince = null
        furthestProgress = maxOf(furthest ?: projection.progress, projection.progress)
        previous = fix
        lastGoodMs = fix.elapsedMs
        lastProgress = projection.progress
        uncertainSince = null
        state = state.copy(status = TrackingStatus.TRACKING, trustedProgress = projection.progress,
            message = "Monitoring your journey. Keep wakemeup running.")
        return state
    }

    fun tick(nowMs: Long): JourneyState {
        if (state.status == TrackingStatus.ALARM) return state
        val age = nowMs - (lastGoodMs ?: startedMs)
        val uncertaintyAge = uncertainSince?.let { nowMs - it } ?: 0
        if (age >= 60_000 || uncertaintyAge >= 60_000) {
            return alarm("Tracking is unreliable. Wake up and check your location.")
        }
        if (age >= 20_000) {
            // Bound possible travel conservatively; this is a warning, not an invented position.
            val remainingToWake = lastProgress?.let { state.leg.wakeProgressMeters - it }
            if (remainingToWake != null && remainingToWake - age / 1000.0 * 40.0 <= (state.accuracy ?: 0.0)) {
                return alarm("Location was lost before your wake-up station, ${state.leg.wakeStation.name}. Check your journey now.")
            }
            return uncertain("Location is stale. An audible warning will sound if tracking does not recover.", nowMs)
        }
        return state
    }

    fun alarm(message: String, kind: AlarmKind = AlarmKind.TRACKING): JourneyState {
        state = state.copy(status = TrackingStatus.ALARM, message = message, alarmReason = message, alarmKind = kind)
        return state
    }

    private fun uncertain(message: String, nowMs: Long): JourneyState {
        if (uncertainSince == null) uncertainSince = nowMs
        state = state.copy(status = TrackingStatus.UNCERTAIN, message = message)
        return state
    }
}
