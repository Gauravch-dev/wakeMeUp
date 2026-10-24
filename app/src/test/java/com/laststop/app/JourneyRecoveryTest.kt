package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class JourneyRecoveryTest {
    private val reverse = MumbaiNetwork.plans("VSH", "PNVL").first()
    private fun saved(plan: JourneyPlan = reverse) = SavedJourney("journey-1", plan.source.id,
        plan.destination.id, plan.id, 0, "file:///alarm_sounds/My alarm.audio", 1_000,
        1_000_000, 7, 1234, 1_000_000)

    @Test fun durableFieldsRetainTheExactReturnRouteAndSound() {
        val original = saved().copy(restartCount = 2)
        assertEquals(original, SavedJourney.fromFields(original.fields()))
        val restored = SavedJourney.fromFields(original.fields())!!
        assertEquals("VSH", restored.plan()!!.source.id)
        assertEquals("PNVL", restored.plan()!!.destination.id)
        assertEquals("Khandeshwar", restored.plan()!!.legs.single().wakeStation.name)
    }

    @Test fun missingCorruptAndUnsupportedSnapshotsCannotResume() {
        assertNull(SavedJourney.fromFields(emptyMap()))
        for ((key, bad) in listOf("schema" to "2", "leg" to "bad", "elapsed" to "-1",
            "pid" to "0", "restarts" to "-2", "boot" to "-1", "alert" to "UNKNOWN")) {
            assertNull(key, SavedJourney.fromFields(saved().fields() + (key to bad)))
        }
        assertNull(SavedJourney.fromFields(saved().fields() - "sound"))
        assertNull(SavedJourney.fromFields(saved().fields() + ("alert" to "APPROACH")))
    }

    @Test fun routeOrLegChangesNeverFallBackToAnotherJourney() {
        assertNull(saved().copy(planId = "removed-route").newEngine(20_000))
        assertNull(saved().copy(legIndex = -1).newEngine(20_000))
        assertNull(saved().copy(legIndex = 99).newEngine(20_000))
        assertNull(saved().copy(sourceId = "UNKNOWN").newEngine(20_000))
    }

    @Test fun rebootAndMonotonicClockResetInvalidateTheSession() {
        assertEquals(0L, saved().remainingMs(20_000, 8))
        assertEquals(0L, saved().remainingMs(500, 7))
        // Even a long uptime after a reboot must not revive a previous trip.
        assertEquals(0L, saved().remainingMs(5_000_000, 8))
    }

    @Test fun restartsDoNotExtendTheOriginalSixHourLimit() {
        val original = saved()
        val restarted = original.copy(processId = 2345, processStartedWallMs = 2_000_000, restartCount = 2)
        val end = original.startedElapsedMs + SavedJourney.MAX_SESSION_MS
        assertEquals(1L, restarted.remainingMs(end - 1, 7))
        assertEquals(0L, restarted.remainingMs(end, 7))
        assertEquals(0L, restarted.remainingMs(end + 100_000, 7))
    }

    @Test fun changingTheWallClockDoesNotInvalidateOrExtendRecovery() {
        val earlierClock = saved().copy(processStartedWallMs = 500_000)
        val roundTrip = SavedJourney.fromFields(earlierClock.fields())!!
        assertEquals(saved().remainingMs(60_000, 7), roundTrip.remainingMs(60_000, 7))
    }

    @Test fun restartedJourneyDoesNotPretendAnOldPositionIsLive() {
        val engine = saved().newEngine(20_000)!!
        assertEquals(TrackingStatus.ACQUIRING, engine.state.status)
        assertNull(engine.state.trustedProgress)
        assertNull(engine.state.lastFixMs)
        assertFalse(JourneyVisuals.fresh(engine.state, 20_000))
        assertTrue(engine.state.message.contains("restarted"))
        assertFalse(JourneyNotificationContent.from(engine.state, 20_000).livePosition)
        val warning = engine.tick(80_000)
        assertEquals(AlarmKind.TRACKING, warning.alarmKind)
        assertFalse(warning.isArrivalAlarm)
    }

    @Test fun restartedJourneyRequiresTwoFreshCompatibleFixesBeforeTracking() {
        val engine = saved().newEngine(20_000)!!
        val first = reverse.legs.single().route.simulatedFix(1_000.0, 21_000)
        val confirming = engine.accept(first, first.elapsedMs)
        assertEquals(TrackingStatus.UNCERTAIN, confirming.status)
        assertNull(confirming.trustedProgress)
        assertEquals(confirming, engine.accept(first, 22_000))
        val second = first.copy(elapsedMs = 26_000)
        assertEquals(TrackingStatus.TRACKING, engine.accept(second, 26_000).status)
        assertEquals(26_000L, engine.state.lastFixMs)
    }

    @Test fun poorOrStaleLocationsCannotConfirmRestoredTracking() {
        val engine = saved().newEngine(20_000)!!
        val fix = reverse.legs.single().route.simulatedFix(1_000.0, 21_000)
        engine.accept(fix, 21_000)
        assertNotEquals(TrackingStatus.TRACKING, engine.accept(fix.copy(elapsedMs = 26_000, accuracy = 600.0), 26_000).status)
        assertNotEquals(TrackingStatus.TRACKING, engine.accept(fix.copy(elapsedMs = 27_000), 60_000).status)
        assertNotEquals(TrackingStatus.TRACKING, engine.accept(fix.copy(elapsedMs = 61_000), 61_000).status)
        assertEquals(TrackingStatus.TRACKING, engine.accept(fix.copy(elapsedMs = 66_000), 66_000).status)
    }

    @Test fun anUnconfirmedArrivalCandidateDoesNotSurviveProcessDeath() {
        val snapshot = saved()
        val near = reverse.legs.single().wakeProgressMeters + 20
        val route = reverse.legs.single().route
        val before = JourneyEngine(reverse, 0, false, 1_000)
        before.accept(route.simulatedFix(near, 2_000), 2_000)
        val after = snapshot.checkpoint(before.state).newEngine(3_000)!!
        assertFalse(after.accept(route.simulatedFix(near, 7_000), 7_000).isArrivalAlarm)
        assertTrue(after.accept(route.simulatedFix(near + 20, 12_000), 12_000).isArrivalAlarm)
    }

    @Test fun anAlreadyConfirmedStationAlarmRemainsLatchedAfterRestart() {
        val route = reverse.legs.single().route
        val before = JourneyEngine(reverse, 0, false, 1_000)
        val arrival = before.confirmAt(route.simulatedFix(reverse.legs.single().wakeProgressMeters + 20, 2_000))
        val snapshot = SavedJourney.fromFields(saved().checkpoint(arrival).fields())!!
        val after = snapshot.newEngine(30_000)!!
        assertTrue(after.state.isArrivalAlarm)
        assertEquals(arrival.alarmReason, after.state.alarmReason)
        assertTrue(after.tick(90_000).isArrivalAlarm)
        assertNull(after.state.lastFixMs)
        assertFalse(JourneyVisuals.fresh(after.state, 30_000))
    }

    @Test fun deviceAndDirectionWarningsStaySilentAndLatchedAcrossRestarts() {
        for (kind in listOf(AlarmKind.SYSTEM, AlarmKind.DIRECTION)) {
            val before = JourneyEngine(reverse, 0, false, 1_000)
            val warning = before.alarm("Check settings or direction.", kind)
            val snapshot = saved().checkpoint(warning)
            val after = snapshot.newEngine(20_000)!!
            assertEquals(kind, after.state.alarmKind)
            assertFalse(after.state.isArrivalAlarm)
            assertEquals(warning.message, after.state.message)
            assertEquals(warning.message, snapshot.checkpoint(after.state).newEngine(40_000)!!.state.message)
        }
    }

    @Test fun gpsWarningsReacquireRatherThanRestoringTheWarningOrStalePosition() {
        val before = JourneyEngine(reverse, 0, false, 1_000)
        val warning = before.tick(61_000)
        val snapshot = saved().checkpoint(warning)
        val after = snapshot.newEngine(70_000)!!
        assertEquals(TrackingStatus.ACQUIRING, after.state.status)
        assertNull(after.state.alarmKind)
        val route = reverse.legs.single().route
        assertFalse(after.accept(route.simulatedFix(1_000.0, 71_000), 71_000).isArrivalAlarm)
        assertEquals(TrackingStatus.TRACKING, after.accept(route.simulatedFix(1_020.0, 76_000), 76_000).status)
    }

    @Test fun restoringATwoStationJourneyAlsoRequiresTwoFreshFixesBeforeAnAlarm() {
        val adjacent = MumbaiNetwork.plans("VSH", "SNCR").first()
        val after = saved(adjacent).newEngine(20_000)!!
        val route = adjacent.legs.single().route
        assertFalse(after.accept(route.simulatedFix(100.0, 21_000), 21_000).isArrivalAlarm)
        assertTrue(after.accept(route.simulatedFix(120.0, 26_000), 26_000).isArrivalAlarm)
    }

    @Test fun fullReturnJourneyWithTwoProcessRestartsAlarmsOnlyNearKhandeshwar() {
        var snapshot = saved()
        val leg = reverse.legs.single()
        val route = leg.route
        var engine = JourneyEngine(reverse, 0, false, 1_000)
        var arrival: JourneyState? = null
        for (step in 0..(route.cumulative.last() / 50).toInt() + 1) {
            val time = 1_000L + step * 5_000L
            val progress = step * 50.0
            if (step == 40 || step == 100) {
                snapshot = SavedJourney.fromFields(snapshot.checkpoint(engine.state).fields())!!
                    .copy(restartCount = snapshot.restartCount + 1)
                engine = snapshot.newEngine(time)!!
                assertNull(engine.state.trustedProgress)
            }
            val state = engine.accept(route.simulatedFix(progress, time), time)
            if (state.status == TrackingStatus.ALARM) {
                assertTrue(state.isArrivalAlarm)
                assertTrue(state.trustedProgress!! >= leg.wakeProgressMeters - 15)
                assertTrue(state.trustedProgress < route.cumulative.last())
                arrival = state
                break
            }
            engine.accept(route.simulatedFix(progress, time), time + 500) // Duplicate callback.
            engine.accept(route.simulatedFix(progress + 2_000, time + 1_000).copy(accuracy = 700.0), time + 1_000)
            assertFalse(engine.tick(time + 2_000).isArrivalAlarm)
        }
        assertEquals(2, snapshot.restartCount)
        assertNotNull("The return journey must still wake near Khandeshwar", arrival)
    }
}
