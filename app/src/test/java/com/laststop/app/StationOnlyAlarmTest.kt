package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class StationOnlyAlarmTest {
    private val plan = MumbaiNetwork.defaultPlan
    private val route = plan.legs.single().route
    private fun engine() = JourneyEngine(plan, 0, false, 0)
    private fun fix(progress: Double, time: Long) = route.simulatedFix(progress, time).copy(accuracy = 10.0)

    @Test fun prolongedGpsLossRemainsVisibleWithoutRequestingTheStationAlarm() {
        val engine = engine()
        engine.accept(fix(1000.0, 1000), 1000)
        assertFalse(engine.tick(21_000).isArrivalAlarm)
        val lost = engine.tick(61_000)
        assertEquals(TrackingStatus.ALARM, lost.status)
        assertEquals(AlarmKind.TRACKING, lost.alarmKind)
        assertFalse(lost.isArrivalAlarm)
        assertTrue(JourneyNotificationContent.from(lost, 61_000).title.startsWith("Silent monitoring warning"))
        assertFalse(JourneyVisuals.fresh(lost, 61_000))
        val report = JourneyAlertReport.format(lost, 61_000, "test time", "0.5.2")
        assertTrue(report.contains("Silent warning; no sound or vibration"))
    }

    @Test fun lossNearSanpadaIsSilentUntilTheStationApproachIsActuallyConfirmed() {
        val engine = engine()
        val wakeStationProgress = route.cumulative[route.stations.lastIndex - 1]
        engine.accept(fix(wakeStationProgress - 1500, 1000), 1000)
        val warning = engine.tick(46_000)
        assertEquals(AlarmKind.TRACKING, warning.alarmKind)
        assertFalse(warning.isArrivalAlarm)
        assertFalse(engine.accept(fix(wakeStationProgress - 200, 47_000), 47_000).isArrivalAlarm)
        val arrival = engine.accept(fix(wakeStationProgress - 150, 52_000), 52_000)
        assertTrue(arrival.isArrivalAlarm)
        assertEquals(AlarmKind.APPROACH, arrival.alarmKind)
        assertTrue(JourneyNotificationContent.from(arrival, 52_000).title.startsWith("Wake up"))
        assertTrue(JourneyAlertReport.format(arrival, 52_000, "test time", "0.5.2")
            .contains("Station alarm with sound and vibration"))
    }

    @Test fun disabledLocationAndDirectionProblemsDoNotUseArrivalAudio() {
        listOf(AlarmKind.SYSTEM, AlarmKind.DIRECTION).forEach { kind ->
            val warning = engine().alarm("Check your journey and device settings.", kind)
            assertEquals(TrackingStatus.ALARM, warning.status)
            assertFalse(warning.isArrivalAlarm)
            assertTrue(JourneyNotificationContent.from(warning, 1000).title.startsWith("Silent"))
        }
    }

    @Test fun gpsRecoveryFarFromSanpadaAndEndingAnAlarmDoNotRequestAudio() {
        val engine = engine()
        engine.accept(fix(1000.0, 1000), 1000)
        engine.tick(61_000)
        val recovery = engine.confirmAt(fix(1100.0, 62_000))
        assertEquals(TrackingStatus.TRACKING, recovery.status)
        assertFalse(recovery.isArrivalAlarm)
        val nearWake = route.cumulative[route.stations.lastIndex - 1] - 200
        val arrival = engine.confirmAt(fix(nearWake, 600_000))
        assertTrue(arrival.isArrivalAlarm)
        assertFalse(arrival.copy(status = TrackingStatus.ENDED).isArrivalAlarm)
    }
}
