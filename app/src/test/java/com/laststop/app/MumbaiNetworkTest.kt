package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class MumbaiNetworkTest {
    @Test fun sourceAndDestinationCanBeSwapped() {
        val forward = MumbaiNetwork.plans("PNVL", "VSH").first()
        val reverse = MumbaiNetwork.plans("VSH", "PNVL").first()
        assertEquals(forward.legs.single().route.stations.reversed(), reverse.legs.single().route.stations)
        assertEquals(forward.totalMeters, reverse.totalMeters, 1.0)
        val engine = JourneyEngine(reverse, 0, false, 0)
        val start = reverse.source
        assertEquals(TrackingStatus.TRACKING, engine.accept(Fix(start.lat, start.lon, 10.0, 1000), 1000).status)
        val target = reverse.destination
        assertEquals(TrackingStatus.ALARM, JourneyEngine(reverse, 0, false, 0)
            .confirmAt(Fix(target.lat, target.lon, 10.0, 1000)).status)
    }

    @Test fun westernAndCentralBranchesHaveTheExpectedEndpoints() {
        listOf("CCG" to "DRD", "CSMT" to "KSRA", "CSMT" to "KHPI", "TNA" to "PNVL", "NEU" to "UNCT", "BSR" to "ROHA").forEach { (a, b) ->
            val plan = MumbaiNetwork.plans(a, b).first()
            assertEquals(a, plan.source.id)
            assertEquals(b, plan.destination.id)
            assertEquals(1, plan.legs.size)
        }
    }

    @Test fun interchangeJourneyWakesAtTheChangeNotTheFinalDestination() {
        val plan = MumbaiNetwork.plans("BVI", "KYN").first()
        assertTrue(plan.legs.size > 1)
        val interchange = plan.legs.first().route.stations.last()
        assertNotEquals(plan.destination.id, interchange.id)
        val engine = JourneyEngine(plan, 0, false, 0)
        val state = engine.confirmAt(Fix(interchange.lat, interchange.lon, 10.0, 1000))
        assertEquals(TrackingStatus.ALARM, state.status)
        assertEquals(AlarmKind.APPROACH, state.alarmKind)
        assertTrue(state.message.contains("change trains"))
        val next = JourneyEngine(plan, 1, false, 2000)
        assertEquals(interchange.id, next.route.stations.first().id)
    }

    @Test fun invalidAndIdenticalStationsCannotCreateAJourney() {
        assertTrue(MumbaiNetwork.plans("PNVL", "PNVL").isEmpty())
        assertTrue(MumbaiNetwork.plans("missing", "VSH").isEmpty())
    }

    @Test fun aliasesMakeFamiliarStationNamesSearchable() {
        assertTrue(MumbaiNetwork.search("VT").any { it.id == "CSMT" })
        assertTrue(MumbaiNetwork.search("Belapur").any { it.id == "BEPR" })
        assertTrue(MumbaiNetwork.search("Elphinstone").any { it.id == "PBHD" })
        assertTrue(MumbaiNetwork.search("Gavhan").any { it.id == "GAVAN" })
        assertTrue(MumbaiNetwork.search("pnvl").any { it.id == "PNVL" })
    }

    @Test fun transHarbourBranchesDoNotConfuseVashiAndJuinagar() {
        val vashi = MumbaiNetwork.plans("TNA", "VSH").first().legs.single().route.stations.map { it.id }
        assertTrue("DIGH" in vashi && "TUH" in vashi && "SNCR" in vashi)
        assertFalse("JNJ" in vashi)
        val panvel = MumbaiNetwork.plans("TNA", "PNVL").first().legs.single().route.stations.map { it.id }
        assertTrue("JNJ" in panvel)
        assertFalse("VSH" in panvel || "SNCR" in panvel)
    }

    @Test fun allStationsAreConnectedAndRouteLegsMeetAtTheirInterchanges() {
        assertEquals(142, MumbaiNetwork.stations.size)
        assertEquals(MumbaiNetwork.stations.size, MumbaiNetwork.byId.size)
        MumbaiNetwork.stations.forEach { station ->
            if (station.id != "PNVL") {
                val plan = MumbaiNetwork.plans("PNVL", station.id).first()
                assertEquals("PNVL", plan.source.id)
                assertEquals(station.id, plan.destination.id)
                plan.legs.zipWithNext().forEach { (a, b) -> assertEquals(a.route.stations.last().id, b.route.stations.first().id) }
                plan.legs.forEach { leg ->
                    assertTrue(leg.route.cumulative.last().isFinite())
                    assertTrue(leg.route.cumulative.last() > 0)
                    assertEquals(leg.route.stations.size, leg.route.stations.distinctBy { it.id }.size)
                }
            }
        }
    }

    @Test fun everyCatalogueCoordinateProjectsToItsStation() {
        MumbaiNetwork.corridors.forEach { corridor ->
            val route = RailwayRoute(corridor.stationIds.map(MumbaiNetwork::station))
            route.stations.forEachIndexed { index, station ->
                assertEquals(route.cumulative[index], route.project(station.lat, station.lon).progress, 1.0)
            }
        }
    }

    @Test fun existingDestinationsMigrateToStableStationIds() {
        assertEquals("VSH", SettingsMigration.destination(9))
        assertEquals("NEU", SettingsMigration.destination(6))
        assertEquals("VSH", SettingsMigration.destination(999))
        assertEquals("VSH", SettingsMigration.destination(null))
    }
}
