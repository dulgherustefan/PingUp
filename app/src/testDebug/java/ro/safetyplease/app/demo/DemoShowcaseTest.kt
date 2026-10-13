package ro.safetyplease.app.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.announce.Evacuation
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.incidents.Responders
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters
import java.io.File

class DemoShowcaseTest {
    private val venue = Venue.parse(File("src/main/assets/venue.json").readText())
    private val now = 1_791_800_000_000L

    @Test
    fun theClosedZoneSendsTheEvacuatedOneToItsAlternative() {
        val zones = DemoShowcase.zones(venue)!!
        val states = DemoShowcase.states(venue, zones)
        assertEquals(venue.zones.size, states.size)
        assertEquals(ZoneState.CLOSED, states[venue.zones.indexOfFirst { it.id == zones.closed }])
        assertEquals(ZoneState.CROWDED, states[venue.zones.indexOfFirst { it.id == zones.crowded }])
        val plan = venue.evacuation.first()
        val open = MapState(emptyMap(), Evacuation(plan.id, setOf(zones.evacuated)))
        val closed = open.copy(zones = mapOf(zones.closed to ZoneState.CLOSED))
        val usual = Evacuations.target(venue, open, zones.evacuated)!!
        val switched = Evacuations.target(venue, closed, zones.evacuated)!!
        assertTrue(switched.switched)
        assertNotEquals(usual.exit.number, switched.exit.number)
    }

    @Test
    fun theUnconsciousReportIsForANearbyResponderAndReadsAsTestData() {
        val zones = DemoShowcase.zones(venue)!!
        val here = venue.zone(zones.evacuated)!!.center
        val incident = DemoShowcase.unconscious(venue, here, zones.evacuated, now)
        assertTrue(DemoScenario.isDemo(incident.incidentId))
        assertEquals(32, incident.incidentId.length)
        assertTrue(Responders.applies(incident))
        assertTrue(Responders.isResponder(DemoShowcase.TEAM))
        assertTrue(distanceMeters(here, Responders.patientPoint(incident)!!) < Responders.RANGE_M)
        assertNotNull(venue.zone(incident.zone))
    }

    @Test
    fun theCrowdLevelsAreForZonesOfTheVenue() {
        val zones = DemoShowcase.zones(venue)!!
        val readings = DemoShowcase.density(zones, now)
        assertTrue(readings.all { venue.zone(it.zone) != null && it.level in 1..5 })
        assertEquals(4, readings.first { it.zone == zones.crowded }.level)
    }
}
