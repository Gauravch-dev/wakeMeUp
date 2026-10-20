package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class JourneyNotificationContentTest {
    private val plan = MumbaiNetwork.defaultPlan
    private val route = plan.legs.first().route
    private fun tracking(progress: Double = 2000.0): JourneyState =
        JourneyEngine(plan, 0, false, 0).accept(route.simulatedFix(progress, 1000), 1000)

    @Test fun acquisitionDoesNotInventADistanceOrProgress() {
        val state = JourneyEngine(plan, 0, false, 0).state
        val content = JourneyNotificationContent.from(state, 1000)
        assertNull(content.fraction)
        assertFalse(content.livePosition)
        assertTrue(content.promote)
        assertEquals("GPS…", content.chip)
        assertTrue(content.summary.contains("Stay awake"))
        assertFalse(content.title.contains("km"))
    }

    @Test fun freshFixShowsGeographicStationsAndNextStation() {
        val state = tracking()
        val content = JourneyNotificationContent.from(state, 1000)
        assertTrue(content.title.startsWith("Vashi · ~"))
        assertTrue(content.summary.contains("stations ahead"))
        assertTrue(content.summary.contains(JourneyVisuals.nextStation(state)!!.name))
        assertTrue(content.livePosition)
        assertTrue(content.chip.length <= 7)
        assertTrue(content.fraction!! > 0)
        assertTrue(content.fraction < 1)
    }

    @Test fun evenBeforeTheEngineTickStaleDistanceIsHidden() {
        val content = JourneyNotificationContent.from(tracking(), 21_000)
        assertFalse(content.livePosition)
        assertEquals("Check", content.chip)
        assertTrue(content.summary.contains("uncertain"))
        assertFalse(content.title.contains("km"))
        assertFalse(content.summary.contains("Next:"))
    }

    @Test fun uncertaintyCannotLeakRejectedDistanceIntoThePill() {
        val confirmed = tracking()
        val doubtful = confirmed.copy(status = TrackingStatus.UNCERTAIN,
            progress = route.cumulative.last(), remainingMeters = 0.0,
            message = "Location jumped unexpectedly.")
        val content = JourneyNotificationContent.from(doubtful, 1000)
        assertEquals(JourneyVisuals.fraction(confirmed), content.fraction)
        assertEquals("Check", content.chip)
        assertFalse(content.livePosition)
        assertTrue(content.detail.contains(doubtful.message))
        assertFalse(content.title.contains("km"))
    }

    @Test fun alarmIsAnExplicitWarningWithoutALivePositionOrPromotion() {
        val engine = JourneyEngine(plan, 0, false, 0)
        val approach = engine.accept(route.simulatedFix(route.cumulative[route.stations.lastIndex - 1] - 200, 1000), 1000)
        val content = JourneyNotificationContent.from(approach, 1000)
        assertEquals("Wake up · Vashi", content.title)
        assertFalse(content.promote)
        assertFalse(content.livePosition)
        assertTrue(content.fraction!! < 1)
        assertEquals(approach.message, content.summary)
    }

    @Test fun trackingFailureDoesNotMasqueradeAsDestinationApproach() {
        val state = tracking().copy(status = TrackingStatus.ALARM, alarmKind = AlarmKind.TRACKING,
            message = "Location was lost near your stop. Check your destination now.")
        val content = JourneyNotificationContent.from(state, 70_000)
        assertTrue(content.title.startsWith("Check your journey"))
        assertEquals(state.message, content.summary)
        assertFalse(content.promote)
    }

    @Test fun transferTargetsCurrentInterchangeRatherThanFinalDestination() {
        val trip = MumbaiNetwork.plans("BVI", "KYN").first()
        val first = JourneyEngine(trip, 0, false, 0)
        val content = JourneyNotificationContent.from(first.state, 1000)
        assertEquals(trip.legs.first().route.stations.last().name, content.title)
        assertTrue(content.detail.contains("Change trains at ${first.state.destination.name}"))
        val second = JourneyEngine(trip, 1, false, 0)
        assertNull(JourneyNotificationContent.from(second.state, 1000).fraction)
    }

    @Test fun reverseJourneyCountsOnlyStationsInTheChosenLeg() {
        val reverse = MumbaiNetwork.plans("VSH", "PNVL").first()
        val engine = JourneyEngine(reverse, 0, false, 0)
        val progress = engine.route.cumulative[1] + 250.0
        val state = engine.accept(engine.route.simulatedFix(progress, 1000), 1000)
        val content = JourneyNotificationContent.from(state, 1000)
        assertTrue(content.title.startsWith("Panvel"))
        assertTrue(content.summary.contains("${engine.route.stations.size - 2} stations ahead"))
        assertTrue(content.summary.contains(engine.route.stations[2].name))
    }

    @Test fun futureTimestampsAndNonFiniteProgressCannotShowLiveDistance() {
        val good = tracking()
        assertFalse(JourneyNotificationContent.from(good, 900).livePosition)
        assertFalse(JourneyNotificationContent.from(good.copy(trustedProgress = Double.NaN), 1000).livePosition)
        assertFalse(JourneyNotificationContent.from(good.copy(trustedProgress = null), 1000).livePosition)
    }

    @Test fun endedJourneyIsNeverPromoted() {
        val content = JourneyNotificationContent.from(tracking().copy(status = TrackingStatus.ENDED), 1000)
        assertFalse(content.promote)
        assertFalse(content.livePosition)
        assertEquals("Journey ended", content.summary)
    }
}
