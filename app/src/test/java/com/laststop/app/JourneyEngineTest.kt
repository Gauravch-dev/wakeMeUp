package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class JourneyEngineTest {
    private val route = MumbaiNetwork.defaultPlan.legs.first().route
    private fun engine(destination: Int, demo: Boolean, started: Long): JourneyEngine {
        val plan = MumbaiNetwork.plans("PNVL", route.stations[destination].id).firstOrNull()
            ?: throw IllegalArgumentException("Source and destination must differ")
        return JourneyEngine(plan, 0, demo, started)
    }
    private fun at(index: Int, time: Long, accuracy: Double = 15.0): Fix {
        val station = route.stations[index]
        return Fix(station.lat, station.lon, accuracy, time)
    }

    @Test fun panvelStartsTrackingWithoutAnArrivalAlarm() {
        val state = engine(9, false, 0).accept(at(0, 1000), 1000)
        assertEquals(TrackingStatus.TRACKING, state.status)
        assertTrue(state.remainingMeters!! > 15_000)
    }

    @Test fun sanpadaTriggersAnEarlyVashiAlarm() {
        val state = engine(9, false, 0).accept(at(8, 1000), 1000)
        assertEquals(TrackingStatus.ALARM, state.status)
        assertTrue(state.message.contains("Vashi"))
    }

    @Test fun earlierDestinationUsesItsOwnPosition() {
        val state = engine(6, false, 0).accept(at(5, 1000), 1000)
        assertEquals(TrackingStatus.ALARM, state.status)
        assertTrue(state.message.contains("Nerul"))
    }

    @Test fun poorAccuracyDoesNotClaimTracking() {
        val engine = engine(9, false, 0)
        assertEquals(TrackingStatus.UNCERTAIN, engine.accept(at(0, 1000, 800.0), 1000).status)
        assertEquals(TrackingStatus.ALARM, engine.tick(61_000).status)
    }

    @Test fun staleAndFutureFixesAreRejected() {
        assertEquals(TrackingStatus.UNCERTAIN, engine(9, false, 0).accept(at(0, 1000), 30_000).status)
        assertEquals(TrackingStatus.UNCERTAIN, engine(9, false, 0).accept(at(0, 5000), 1000).status)
    }

    @Test fun noLocationAtAllProducesAnAudibleWarningDecision() {
        val engine = engine(9, false, 0)
        assertEquals(TrackingStatus.UNCERTAIN, engine.tick(20_000).status)
        assertEquals(TrackingStatus.ALARM, engine.tick(60_000).status)
    }

    @Test fun lostLocationNearDestinationWarnsEarly() {
        val engine = engine(9, false, 0)
        assertEquals(TrackingStatus.TRACKING, engine.accept(at(7, 1000), 1000).status)
        assertEquals(TrackingStatus.ALARM, engine.tick(46_000).status)
        assertTrue(engine.state.message.contains("lost"))
    }

    @Test fun distantSignalGapDoesNotInventProgress() {
        val engine = engine(9, false, 0)
        val initial = engine.accept(at(0, 1000), 1000)
        val stale = engine.tick(25_000)
        assertEquals(TrackingStatus.UNCERTAIN, stale.status)
        assertEquals(initial.progress, stale.progress)
        assertEquals(TrackingStatus.ALARM, engine.tick(61_000).status)
    }

    @Test fun recoveryClearsUncertainty() {
        val engine = engine(9, false, 0)
        engine.accept(at(0, 1000), 1000)
        engine.tick(25_000)
        assertEquals(TrackingStatus.TRACKING, engine.accept(at(0, 30_000), 30_000).status)
        assertEquals(TrackingStatus.TRACKING, engine.tick(35_000).status)
    }

    @Test fun oneImpossibleGpsJumpCannotTriggerArrival() {
        val engine = engine(9, false, 0)
        engine.accept(at(0, 1000), 1000)
        assertEquals(TrackingStatus.UNCERTAIN, engine.accept(at(9, 2000), 2000).status)
        assertEquals(TrackingStatus.TRACKING, engine.accept(at(0, 3000), 3000).status)
    }

    @Test fun fixFarAwayFromCorridorIsNotAccepted() {
        val state = engine(9, false, 0).accept(Fix(19.2, 72.8, 10.0, 1000), 1000)
        assertEquals(TrackingStatus.UNCERTAIN, state.status)
        assertNull(state.progress)
    }

    @Test fun travellingBackwardsWarnsInsteadOfContinuingSilently() {
        val engine = engine(9, false, 0)
        engine.accept(at(4, 1000), 1000)
        engine.accept(at(3, 61_000), 61_000)
        assertEquals(TrackingStatus.ALARM, engine.accept(at(3, 82_000), 82_000).status)
        assertTrue(engine.state.message.contains("direction"))
    }

    @Test fun alarmStaysLatchedUntilJourneyEnds() {
        val engine = engine(9, false, 0)
        engine.accept(at(8, 1000), 1000)
        assertEquals(TrackingStatus.ALARM, engine.accept(at(0, 2000), 2000).status)
        assertEquals(TrackingStatus.ALARM, engine.tick(3000).status)
    }

    @Test fun invalidNumbersAndOutOfOrderLocationsCannotBeTrusted() {
        val engine = engine(9, false, 0)
        assertEquals(TrackingStatus.UNCERTAIN, engine.accept(Fix(Double.NaN, 73.0, 5.0, 1000), 1000).status)
        engine.accept(at(0, 2000), 2000)
        assertEquals(TrackingStatus.UNCERTAIN, engine.accept(at(0, 1000), 2500).status)
    }

    @Test fun simulatedJourneyReachesAlarmWithoutRealLocation() {
        val engine = engine(9, true, 0)
        repeat(37) { step ->
            val time = (step + 1) * 1000L
            engine.accept(route.simulatedFix(route.cumulative.last() * step / 36, time), time)
        }
        assertEquals(TrackingStatus.ALARM, engine.state.status)
    }

    @Test fun routeProjectionMatchesEveryStation() {
        route.stations.forEachIndexed { index, station ->
            val projection = route.project(station.lat, station.lon)
            assertEquals(route.cumulative[index], projection.progress, 1.0)
            assertEquals(0.0, projection.offset, 1.0)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun boardingStationCannotBeChosenAsDestination() { engine(0, false, 0) }
}
