package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class JourneyVisualsTest {
    private val plan = MumbaiNetwork.defaultPlan
    private val route = plan.legs.first().route
    private fun fix(progress: Double, time: Long, accuracy: Double = 10.0) =
        route.simulatedFix(progress, time).copy(accuracy = accuracy)

    @Test fun noConfirmedFixHasNoDisplayedTrainPosition() {
        val state = JourneyState(progress = 5000.0)
        assertNull(JourneyVisuals.fraction(state))
        assertNull(JourneyVisuals.nextStation(state))
        assertFalse(JourneyVisuals.fresh(state, 1000))
    }

    @Test fun displayUsesConfirmedDistanceInsteadOfAnUntrustedReading() {
        val total = route.cumulative.last()
        val state = JourneyState(progress = total * .9, trustedProgress = total * .25)
        assertEquals(.25f, JourneyVisuals.fraction(state)!!, .0001f)
        assertEquals(0f, JourneyVisuals.fraction(state.copy(trustedProgress = -500.0))!!, .0001f)
        assertEquals(1f, JourneyVisuals.fraction(state.copy(trustedProgress = total * 2))!!, .0001f)
        assertNull(JourneyVisuals.fraction(state.copy(trustedProgress = Double.NaN)))
    }

    @Test fun gpsJumpCannotMoveTheTrainMarker() {
        val engine = JourneyEngine(plan, 0, false, 0)
        val first = engine.accept(fix(2000.0, 1000), 1000)
        val rejected = engine.accept(fix(route.cumulative.last(), 2000), 2000)
        assertEquals(TrackingStatus.UNCERTAIN, rejected.status)
        assertEquals(first.trustedProgress, rejected.trustedProgress)
        assertFalse(JourneyVisuals.fresh(rejected, 2000))
    }

    @Test fun reverseDirectionCheckCannotMoveConfirmedProgressBackwards() {
        val engine = JourneyEngine(plan, 0, false, 0)
        val first = engine.accept(fix(4000.0, 1000), 1000)
        val reverse = engine.accept(fix(3000.0, 31_000), 31_000)
        assertEquals(TrackingStatus.UNCERTAIN, reverse.status)
        assertTrue(reverse.progress!! < first.progress!!)
        assertEquals(first.trustedProgress, reverse.trustedProgress)
    }

    @Test fun signalLossPausesAndRecoveryRestoresConfirmedProgress() {
        val engine = JourneyEngine(plan, 0, false, 0)
        val good = engine.accept(fix(2000.0, 1000), 1000)
        val lost = engine.tick(25_000)
        assertEquals(good.trustedProgress, lost.trustedProgress)
        assertFalse(JourneyVisuals.fresh(lost, 25_000))
        val recovered = engine.accept(fix(2200.0, 30_000), 30_000)
        assertTrue(JourneyVisuals.fresh(recovered, 30_000))
        assertTrue(JourneyVisuals.fraction(recovered)!! > JourneyVisuals.fraction(good)!!)
    }

    @Test fun approachAlarmDoesNotPretendTheTrainHasArrived() {
        val engine = JourneyEngine(plan, 0, false, 0)
        val approach = engine.accept(fix(route.cumulative[route.stations.lastIndex - 1] - 200.0, 1000), 1000)
        assertEquals(AlarmKind.APPROACH, approach.alarmKind)
        assertTrue(JourneyVisuals.fraction(approach)!! < 1f)
        assertTrue(JourneyVisuals.fresh(approach, 2000))
        assertFalse(JourneyVisuals.fresh(approach, 22_000))
    }

    @Test fun everyNewLegStartsWithAnUnknownPosition() {
        val trip = MumbaiNetwork.plans("BVI", "KYN").first()
        val next = JourneyEngine(trip, 1, false, 0)
        assertNull(JourneyVisuals.fraction(next.state))
        val source = next.route.stations.first()
        val started = next.accept(Fix(source.lat, source.lon, 10.0, 1000), 1000)
        assertEquals(0f, JourneyVisuals.fraction(started)!!, .0001f)
    }

    @Test fun futureTimestampsAndOldLocationsCannotAnimate() {
        val state = JourneyState(status = TrackingStatus.TRACKING, trustedProgress = 1000.0, lastFixMs = 1000)
        assertFalse(JourneyVisuals.fresh(state, 900))
        assertTrue(JourneyVisuals.fresh(state, 20_999))
        assertFalse(JourneyVisuals.fresh(state, 21_000))
    }
}
