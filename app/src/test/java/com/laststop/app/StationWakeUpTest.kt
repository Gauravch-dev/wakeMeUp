package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class StationWakeUpTest {
    private val stations = listOf(
        Station("A", "Start", 19.0, 73.0),
        Station("B", "Previous station", 19.0, 73.1),
        Station("C", "Destination", 19.0, 73.2),
    )
    private val route = RailwayRoute(stations)
    private val plan = JourneyPlan(listOf(JourneyLeg("test", "Test line", route)))
    private fun engine() = JourneyEngine(plan, 0, false, 0)

    @Test fun wakeUpUsesPreviousStationEvenWhenDestinationIsManyKilometresAway() {
        val early = engine().accept(route.simulatedFix(route.cumulative[1] - 500, 1000), 1000)
        assertEquals(TrackingStatus.TRACKING, early.status)
        val approach = engine().confirmAt(route.simulatedFix(route.cumulative[1] - 200, 1000))
        assertEquals(AlarmKind.APPROACH, approach.alarmKind)
        assertTrue(approach.remainingMeters!! > 8000)
        assertTrue(approach.message.contains("Approaching Previous station"))
        assertTrue(approach.message.contains("Destination"))
    }

    @Test fun passingTheWakeStationBetweenReadingsCannotMissTheAlarm() {
        val engine = engine()
        engine.accept(route.simulatedFix(route.cumulative[1] - 600, 1000), 1000)
        val alarm = engine.confirmAt(route.simulatedFix(route.cumulative[1] + 600, 41_000))
        assertEquals(AlarmKind.APPROACH, alarm.alarmKind)
        assertTrue(alarm.message.contains("reached or passed Previous station"))
        assertTrue(alarm.trustedProgress!! < route.cumulative.last())
    }

    @Test fun startingMonitoringAfterTheWakeStationSoundsImmediately() {
        val alarm = engine().confirmAt(route.simulatedFix(route.cumulative[1] + 1000, 1000))
        assertEquals(AlarmKind.APPROACH, alarm.alarmKind)
    }

    @Test fun gpsLossWarnsBeforeTheWakeStationEvenFarFromTheDestination() {
        val engine = engine()
        val initial = engine.accept(route.simulatedFix(route.cumulative[1] - 1000, 1000), 1000)
        assertEquals(TrackingStatus.TRACKING, initial.status)
        val warning = engine.tick(21_000)
        assertEquals(AlarmKind.TRACKING, warning.alarmKind)
        assertTrue(warning.message.contains("wake-up station, Previous station"))
        assertEquals(initial.trustedProgress, warning.trustedProgress)
    }

    @Test fun nextStationTripWakesAtTheSourceInsteadOfPromisingTimeToSleep() {
        val short = JourneyPlan(listOf(JourneyLeg("test", "Test line", RailwayRoute(stations.take(2)))))
        assertTrue(short.legs.single().wakeAtSource)
        assertEquals("A", short.legs.single().wakeStation.id)
        val state = JourneyEngine(short, 0, false, 0).accept(Fix(19.0, 73.0, 10.0, 1000), 1000)
        assertEquals(AlarmKind.APPROACH, state.alarmKind)
        assertTrue(state.message.contains("Stay awake"))
        assertEquals(0.0, state.trustedProgress!!, 1.0)
    }

    @Test fun poorLocationCannotPretendTheWakeStationHasBeenReached() {
        val station = stations[1]
        val state = engine().accept(Fix(station.lat, station.lon, 500.0, 1000), 1000)
        assertEquals(TrackingStatus.ACQUIRING, state.status)
        assertNull(state.alarmKind)
        assertNull(state.trustedProgress)
    }

    @Test fun everyCorridorUsesThePreviousStationInEitherDirection() {
        MumbaiNetwork.corridors.forEach { corridor ->
            listOf(corridor.stationIds, corridor.stationIds.reversed()).forEach { ids ->
                val leg = JourneyLeg(corridor.id, corridor.name, RailwayRoute(ids.map(MumbaiNetwork::station)))
                val trip = JourneyPlan(listOf(leg))
                assertEquals(ids[ids.lastIndex - 1], leg.wakeStation.id)
                val station = leg.wakeStation
                val state = JourneyEngine(trip, 0, false, 0).confirmAt(Fix(station.lat, station.lon, 10.0, 1000))
                assertEquals("${corridor.name}: ${ids.first()} → ${ids.last()}", AlarmKind.APPROACH, state.alarmKind)
            }
        }
    }

    @Test fun eachTransferLegGetsItsOwnWakeStation() {
        val trip = MumbaiNetwork.plans("BVI", "KYN").first()
        trip.legs.forEachIndexed { index, leg ->
            val station = leg.wakeStation
            val state = JourneyEngine(trip, index, false, 0).confirmAt(Fix(station.lat, station.lon, 10.0, 1000))
            assertEquals(AlarmKind.APPROACH, state.alarmKind)
            assertEquals(leg.route.stations.last().id, state.destination.id)
            if (index < trip.legs.lastIndex) assertTrue(state.message.contains("change trains"))
            else assertFalse(state.message.contains("change trains"))
        }
    }

    @Test fun notificationsIdentifyTheStationBeforeTheCurrentDestination() {
        val state = engine().accept(route.simulatedFix(1000.0, 1000), 1000)
        val content = JourneyNotificationContent.from(state, 1000)
        assertTrue(content.detail.contains("Wake near Previous station · One station before Destination"))
        assertTrue(content.livePosition)
    }
}
