package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class JourneyNoiseRegressionTest {
    private val plan = MumbaiNetwork.defaultPlan
    private val route = plan.legs.single().route
    private fun engine() = JourneyEngine(plan, 0, false, 0)
    private fun fix(progress: Double, time: Long, accuracy: Double = 10.0) =
        route.simulatedFix(progress, time).copy(accuracy = accuracy)
    private val nearWake get() = route.cumulative[route.stations.lastIndex - 1] - 200.0

    @Test fun duplicateAndOutOfOrderBatchEntriesDoNotFlickerTheStatus() {
        val engine = engine()
        val good = engine.accept(fix(1000.0, 5000), 5000)
        assertEquals(good, engine.accept(fix(1000.0, 5000), 5500))
        assertEquals(good, engine.accept(fix(900.0, 4000), 5500))
    }

    @Test fun briefPoorAccuracyKeepsTheFreshConfirmedPosition() {
        val engine = engine()
        val good = engine.accept(fix(1000.0, 1000), 1000)
        val poor = engine.accept(fix(1200.0, 6000, 700.0), 6000)
        assertEquals(TrackingStatus.TRACKING, poor.status)
        assertEquals(good.message, poor.message)
        assertEquals(good.trustedProgress, poor.trustedProgress)
        assertEquals(good.lastFixMs, poor.lastFixMs)
        assertFalse(JourneyVisuals.fresh(engine.tick(21_000), 21_000))
        assertEquals(AlarmKind.TRACKING, engine.tick(61_000).alarmKind)
    }

    @Test fun aSingleBadJumpCannotMoveTheMarkerOrChangeAFreshTrackingStatus() {
        val engine = engine()
        val good = engine.accept(fix(1000.0, 1000), 1000)
        val jump = engine.accept(fix(nearWake, 2000), 2000)
        assertEquals(TrackingStatus.TRACKING, jump.status)
        assertEquals(good.trustedProgress, jump.trustedProgress)
        assertNull(jump.alarmKind)
    }

    @Test fun startupAccuracyChangesDoNotAlternateBetweenAcquiringAndErrors() {
        val engine = engine()
        repeat(5) { index ->
            val time = 1000L + index * 1000
            assertEquals(TrackingStatus.ACQUIRING,
                engine.accept(fix(0.0, time, 500.0 + index * 50), time).status)
        }
    }

    @Test fun aSingleFreshReadingNearSanpadaDoesNotImmediatelyLatchArrival() {
        val engine = engine()
        val pending = engine.accept(fix(nearWake, 1000), 1000)
        assertNotEquals(TrackingStatus.ALARM, pending.status)
        assertNull(pending.trustedProgress)
        assertEquals(AlarmKind.APPROACH, engine.accept(fix(nearWake + 50, 6000), 6000).alarmKind)
    }

    @Test fun duplicateCandidateAndTimerTicksCannotConfirmArrival() {
        val engine = engine()
        engine.accept(fix(nearWake, 1000), 1000)
        assertNotEquals(AlarmKind.APPROACH, engine.accept(fix(nearWake, 1000), 6000).alarmKind)
        assertNotEquals(AlarmKind.APPROACH, engine.tick(10_000).alarmKind)
    }

    @Test fun returningToAFarLocationCancelsTheUnconfirmedArrival() {
        val engine = engine()
        engine.accept(fix(nearWake, 1000), 1000)
        engine.accept(fix(1000.0, 6000), 6000)
        assertEquals(TrackingStatus.TRACKING, engine.state.status)
        assertNull(engine.state.alarmKind)
        assertTrue(engine.state.trustedProgress!! < 1500)
    }

    @Test fun trackingWarningRecoversOnlyAfterTwoConsistentFreshFixes() {
        val engine = engine()
        engine.accept(fix(1000.0, 1000), 1000)
        assertEquals(AlarmKind.TRACKING, engine.tick(61_000).alarmKind)
        assertEquals(TrackingStatus.ALARM, engine.accept(fix(1100.0, 62_000), 62_000).status)
        val recovered = engine.accept(fix(1200.0, 67_000), 67_000)
        assertEquals(TrackingStatus.TRACKING, recovered.status)
        assertNull(recovered.alarmKind)
        assertNull(recovered.alarmReason)
    }

    @Test fun recoveryNearTheWakeStationBecomesArrivalRatherThanSilencingIt() {
        val engine = engine()
        engine.accept(fix(1000.0, 1000), 1000)
        engine.tick(601_000)
        assertEquals(AlarmKind.TRACKING, engine.accept(fix(nearWake, 602_000), 602_000).alarmKind)
        val arrival = engine.accept(fix(nearWake + 30, 607_000), 607_000)
        assertEquals(AlarmKind.APPROACH, arrival.alarmKind)
        assertEquals(TrackingStatus.ALARM, arrival.status)
    }

    @Test fun aLaterTrackingFailureCannotRelabelALatchedStationAlarm() {
        val engine = engine()
        engine.accept(fix(nearWake, 1000), 1000)
        val arrival = engine.accept(fix(nearWake + 30, 6000), 6000)
        assertEquals(arrival, engine.alarm("Device location is switched off."))
    }

    @Test fun panvelToVashiCannotArriveAtAnyOfTheEarlierStations() {
        // All stations before Sanpada, including where an alarm 4–5 stops early
        // would have occurred. Repeated accurate fixes must keep tracking.
        route.stations.dropLast(2).forEach { station ->
            val engine = engine()
            val state = engine.confirmAt(Fix(station.lat, station.lon, 10.0, 1000))
            assertEquals(station.name, TrackingStatus.TRACKING, state.status)
            assertNull(station.name, state.alarmKind)
        }
    }

    @Test fun poorAccuracyBetweenGoodCandidatesCannotConfirmOrPreventTheAlarm() {
        val engine = engine()
        engine.accept(fix(nearWake, 1000), 1000)
        assertNotEquals(AlarmKind.APPROACH, engine.accept(fix(nearWake, 6000, 600.0), 6000).alarmKind)
        assertEquals(AlarmKind.APPROACH, engine.accept(fix(nearWake, 11_000), 11_000).alarmKind)
    }

    @Test fun candidatesTooFarApartCannotConfirmEachOther() {
        val engine = engine()
        engine.accept(fix(nearWake, 1000), 1000)
        assertNotEquals(AlarmKind.APPROACH, engine.accept(fix(nearWake, 21_000), 21_000).alarmKind)
        assertEquals(AlarmKind.APPROACH, engine.accept(fix(nearWake, 26_000), 26_000).alarmKind)
    }

    @Test fun aFutureTimestampCannotBlockLaterValidUpdates() {
        val engine = engine()
        engine.accept(fix(1000.0, 1000), 1000)
        engine.accept(fix(nearWake, 999_000), 2000)
        val good = engine.accept(fix(1100.0, 6000), 6000)
        assertEquals(TrackingStatus.TRACKING, good.status)
        assertEquals(6000L, good.lastFixMs)
    }

    @Test fun systemWarningsRemainLatchedUntilTheUserEndsTheJourney() {
        val engine = engine()
        val warning = engine.alarm("Location permission was removed.", AlarmKind.SYSTEM)
        engine.confirmAt(fix(1000.0, 1000))
        assertEquals(warning, engine.state)
        assertTrue(warning.message.contains("not an arrival alert"))
    }

    @Test fun completePanvelVashiJourneyWithNoisyUpdatesAlarmsOnlyNearSanpada() {
        val engine = engine()
        var arrival: JourneyState? = null
        for (step in 0..(route.cumulative.last() / 50).toInt() + 1) {
            val progress = step * 50.0
            val time = 1000L + step * 5000L
            val state = engine.accept(fix(progress, time), time)
            if (state.status == TrackingStatus.ALARM) {
                assertEquals(AlarmKind.APPROACH, state.alarmKind)
                assertTrue(state.trustedProgress!! >= plan.legs.single().wakeProgressMeters - 10)
                assertTrue(state.trustedProgress < route.cumulative.last())
                arrival = state
                break
            }
            engine.accept(fix(progress, time), time + 500) // Replayed batch entry.
            engine.accept(fix(progress + 2000, time + 1000, 700.0), time + 1000)
            assertNotEquals(TrackingStatus.ALARM, engine.tick(time + 2000).status)
        }
        assertNotNull("The genuine Sanpada approach must still ring despite noisy samples", arrival)
    }
}
