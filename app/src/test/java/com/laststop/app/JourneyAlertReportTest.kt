package com.laststop.app

import org.junit.Assert.*
import org.junit.Test

class JourneyAlertReportTest {
    @Test fun reportExplainsMonitoringFailureWithoutRawCoordinates() {
        val engine = JourneyEngine(MumbaiNetwork.defaultPlan, 0, false, 0)
        engine.accept(Fix(18.9909392, 73.120605, 10.0, 1000), 1000)
        val warning = engine.tick(61_000)
        val report = JourneyAlertReport.format(warning, 61_000, "test time", "0.5.1")
        assertTrue(report.contains("Alert type: TRACKING"))
        assertTrue(report.contains("not an arrival alert"))
        assertTrue(report.contains("Planned wake-up station: Sanpada"))
        assertTrue(report.contains("Last confirmed fix age: 60 s"))
        assertFalse(report.contains("18.9909392"))
        assertFalse(report.contains("73.120605"))
    }

    @Test fun reportMakesAnAlarmWithoutAnyConfirmedFixExplicit() {
        val state = JourneyEngine(MumbaiNetwork.defaultPlan, 0, false, 0).tick(60_000)
        val report = JourneyAlertReport.format(state, 60_000, "test time", "0.5.1")
        assertTrue(report.contains("No confirmed fix"))
        assertTrue(report.contains("Last confirmed accuracy: Unknown"))
        assertTrue(report.contains("Confirmed progress along this leg: Unknown"))
    }
}
