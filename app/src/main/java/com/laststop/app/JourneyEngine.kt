package com.laststop.app

enum class TrackingStatus { IDLE, ACQUIRING, TRACKING, UNCERTAIN, ALARM, ENDED }
enum class AlarmKind { APPROACH, TRACKING, DIRECTION, SYSTEM }
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
    val lastLocationIssue: String? = null,
) {
    val leg get() = plan.legs[legIndex]
    val destination get() = leg.route.stations.last()
    val hasNextLeg get() = legIndex < plan.legs.lastIndex
    val isArrivalAlarm get() = status == TrackingStatus.ALARM && alarmKind == AlarmKind.APPROACH
}

/** Pure journey rules; monotonic timestamps avoid changes to the phone clock. */
class JourneyEngine(val plan: JourneyPlan, val legIndex: Int, val demo: Boolean, val startedMs: Long,
    private var recovering: Boolean = false) {
    init {
        require(legIndex in plan.legs.indices)
    }
    val route = plan.legs[legIndex].route
    var state = JourneyState(status = TrackingStatus.ACQUIRING, plan = plan, legIndex = legIndex,
        demo = demo,
        message = if (recovering) "Monitoring restarted after an interruption. Checking fresh location; stay awake until tracking is confirmed."
            else "Finding your location. Stay awake until tracking is confirmed.")
        private set
    private var previous: Fix? = null
    private var lastGoodMs: Long? = null
    private var lastProgress: Double? = null
    private var uncertainSince: Long? = null
    private var backwardsSince: Long? = null
    private var furthestProgress: Double? = null
    private var lastObservedMs: Long? = null
    private var approachCandidate: Fix? = null
    private var recoveryCandidate: Fix? = null

    fun accept(fix: Fix, nowMs: Long): JourneyState {
        if (state.status == TrackingStatus.ALARM && state.alarmKind != AlarmKind.TRACKING) return state
        // Duplicate/older entries are not evidence of location failure. In particular,
        // never let a delayed batch undo the status of a newer confirmed reading.
        if (lastObservedMs?.let { fix.elapsedMs <= it } == true) return state
        if (nowMs - fix.elapsedMs !in 0..19_999) {
            return reject("The location timestamp was stale or in the future.", nowMs)
        }
        lastObservedMs = fix.elapsedMs
        if (!fix.lat.isFinite() || !fix.lon.isFinite() || fix.lat !in -90.0..90.0 ||
            fix.lon !in -180.0..180.0 || !fix.accuracy.isFinite() || fix.accuracy < 0.0) {
            return reject("The location contained invalid coordinates or accuracy.", nowMs)
        }
        if (fix.accuracy > 150.0) {
            // An imprecise sample provides no positional evidence. It must neither
            // confirm a candidate nor erase two otherwise consistent good readings.
            return reject("The location accuracy was worse than 150 m.", nowMs, resetConfirmation = false)
        }
        val projection = route.project(fix.lat, fix.lon)
        if (projection.offset > 800.0 + fix.accuracy) {
            return reject("The location was away from the selected route.", nowMs)
        }
        previous?.let { old ->
            if (!compatible(old, fix)) {
                return reject("The location jumped farther than plausible train travel.", nowMs)
            }
        }
        val target = route.stations.last()
        val remaining = route.cumulative.last() - projection.progress
        state = state.copy(progress = projection.progress)
        val furthest = furthestProgress
        if (!demo && furthest != null && projection.progress < furthest - 500.0) {
            approachCandidate = null; recoveryCandidate = null
            if (backwardsSince == null) backwardsSince = nowMs
            if (nowMs - backwardsSince!! >= 20_000) return alarm("You appear to be travelling away from your destination. Check your direction.", AlarmKind.DIRECTION)
            return uncertain("Checking direction: movement appears to be away from your stop.", nowMs)
        }
        backwardsSince = null
        if (projection.progress + fix.accuracy >= state.leg.wakeProgressMeters) {
            recoveryCandidate = null
            if ((!state.leg.wakeAtSource || recovering) && !confirmed(approachCandidate, fix)) {
                if (!sameConfirmationWindow(approachCandidate, fix)) approachCandidate = fix
                state = state.copy(lastLocationIssue = "Confirming the position near ${state.leg.wakeStation.name} with another fresh reading.")
                return uncertain("Confirming your wake-up station. Stay awake while location is checked.", nowMs)
            }
            state = state.copy(trustedProgress = projection.progress, remainingMeters = remaining.coerceAtLeast(0.0),
                accuracy = fix.accuracy, lastFixMs = fix.elapsedMs, lastLocationIssue = null)
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
        approachCandidate = null
        if (recovering || state.status == TrackingStatus.ALARM && state.alarmKind == AlarmKind.TRACKING) {
            if (!confirmed(recoveryCandidate, fix)) {
                if (!sameConfirmationWindow(recoveryCandidate, fix)) recoveryCandidate = fix
                return if (recovering) uncertain("Monitoring restarted. Confirming fresh location before tracking resumes.", nowMs) else state
            }
        }
        recoveryCandidate = null
        recovering = false
        furthestProgress = maxOf(furthest ?: projection.progress, projection.progress)
        previous = fix
        lastGoodMs = fix.elapsedMs
        lastProgress = projection.progress
        uncertainSince = null
        state = state.copy(status = TrackingStatus.TRACKING, trustedProgress = projection.progress,
            remainingMeters = remaining.coerceAtLeast(0.0), accuracy = fix.accuracy, lastFixMs = fix.elapsedMs,
            alarmKind = null, alarmReason = null, lastLocationIssue = null,
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
            return uncertain("Location is stale. GPS warnings are silent. Stay awake until tracking recovers.", nowMs)
        }
        return state
    }

    fun alarm(message: String, kind: AlarmKind = AlarmKind.TRACKING): JourneyState {
        // A later GPS/system failure must never overwrite a confirmed station alert.
        if (state.status == TrackingStatus.ALARM && (state.alarmKind != AlarmKind.TRACKING || kind == AlarmKind.TRACKING)) return state
        approachCandidate = null; recoveryCandidate = null
        val reason = if (kind == AlarmKind.TRACKING || kind == AlarmKind.SYSTEM)
            "Silent monitoring warning. $message This is not an arrival alert. The station alarm needs confirmed location." else message
        state = state.copy(status = TrackingStatus.ALARM, message = reason, alarmReason = reason, alarmKind = kind)
        return state
    }

    internal fun restoreLatchedAlert(kind: AlarmKind, reason: String) {
        require(kind != AlarmKind.TRACKING)
        state = state.copy(status = TrackingStatus.ALARM, message = reason, alarmReason = reason, alarmKind = kind)
    }

    private fun uncertain(message: String, nowMs: Long): JourneyState {
        if (state.status == TrackingStatus.ALARM) return state
        if (uncertainSince == null) uncertainSince = nowMs
        state = state.copy(status = TrackingStatus.UNCERTAIN, message = message)
        return state
    }

    private fun reject(reason: String, nowMs: Long, resetConfirmation: Boolean = true): JourneyState {
        if (resetConfirmation) { approachCandidate = null; recoveryCandidate = null }
        state = state.copy(lastLocationIssue = reason)
        if (state.status == TrackingStatus.ALARM) return state
        // Keep the last good fix for its existing freshness window; never refresh its
        // timestamp or move its marker using a poor sample. tick() still warns on loss.
        if (state.status == TrackingStatus.TRACKING && lastGoodMs?.let { nowMs - it in 0..19_999 } == true) return state
        if (state.status == TrackingStatus.ACQUIRING && nowMs - startedMs < 20_000) return state
        return uncertain("Location is weak. Waiting for a reliable update. Stay awake until tracking recovers.", nowMs)
    }

    private fun compatible(first: Fix, second: Fix): Boolean {
        val seconds = (second.elapsedMs - first.elapsedMs) / 1000.0
        return seconds > 0 && (demo || RailwayRoute.distance(first.lat, first.lon, second.lat, second.lon) <=
            seconds * 55.0 + first.accuracy + second.accuracy + 100.0)
    }

    private fun sameConfirmationWindow(first: Fix?, second: Fix): Boolean = first != null &&
        second.elapsedMs - first.elapsedMs in 1..15_000 && compatible(first, second)

    private fun confirmed(first: Fix?, second: Fix): Boolean =
        sameConfirmationWindow(first, second) && second.elapsedMs - first!!.elapsedMs >= 3_000
}
