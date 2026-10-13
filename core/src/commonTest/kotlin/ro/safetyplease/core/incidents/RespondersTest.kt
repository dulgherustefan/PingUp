package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.distanceMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RespondersTest {
    private val patient = GeoPoint(44.0000, 28.0000)

    /** About 111 m north per 0.001° of latitude. */
    private fun north(meters: Int) = GeoPoint(patient.lat + meters / 111_320.0, patient.lon)

    private fun incident(
        unconscious: Boolean = true,
        breathes: Int = Answer.UNASKED,
        status: Int = AckStatus.RECEIVED,
        at: GeoPoint? = patient,
        zone: String = "main-stage",
    ) = StaffIncident(
        incidentId = "a", category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = zone, lat = at?.lat, lon = at?.lon,
        reportedAt = 0, receivedAt = 0, hops = 1, status = status, unconscious = unconscious, breathes = breathes,
    )

    private fun medic(at: GeoPoint?, team: String = "Medical 2", zone: String = "camping") = StaffProfile(console = false, team = team, zone = zone, position = at)

    @Test
    fun medicsAndFirstAidersInRangeHearItAtOnce() {
        assertTrue(Responders.alerts(medic(north(250)), incident()))
        assertTrue(Responders.alerts(medic(north(250), team = "Prim ajutor 3"), incident()))
        assertTrue(Responders.alerts(medic(north(250)), incident(unconscious = false, breathes = Answer.NO)), "a medical report saying it doesn't breathe")
        assertFalse(Responders.alerts(medic(north(350)), incident()), "out of range")
        assertFalse(Responders.alerts(medic(north(50), team = "Securitate 1"), incident()), "not a responder's team")
        assertFalse(Responders.alerts(medic(north(50)), incident(unconscious = false)), "an ordinary medical report")
        assertFalse(Responders.alerts(StaffProfile(console = true, team = "Medical"), incident()), "the console has its own alarm")
        assertTrue(Responders.alerts(medic(null, zone = "main-stage"), incident()), "without a fix, the zone stands in")
        assertTrue(Responders.alerts(medic(north(900), zone = "main-stage"), incident(at = null)), "and without the patient's position")
        assertFalse(Responders.alerts(medic(null), incident()))
    }

    @Test
    fun routingRingsTheRespondersUntilSomeoneHasIt() {
        val near = medic(north(100))
        assertEquals(Route(AlertLevel.INSISTENT, AlertReason.RESPONDER), Routing.route(near, incident(), 0))
        assertEquals(AlertLevel.SILENT, Routing.route(near, incident(status = AckStatus.ACKNOWLEDGED), 0).level)
        assertEquals(AlertLevel.SILENT, Routing.route(medic(north(400)), incident(), 0).level, "a medic far away waits for the widening")
    }

    @Test
    fun theDefibrillatorIsTheOneThatAddsTheLeastWalking() {
        val me = north(200)
        // one by the patient but behind them, one on the way, one far off to the side
        val behind = Poi(Venue.DEA, patient.lat - 60 / 111_320.0, patient.lon, "Lângă bar")
        val onTheWay = Poi(Venue.DEA, north(120).lat, patient.lon, "Postul medical")
        val farOff = Poi(Venue.DEA, north(100).lat, patient.lon + 0.01, "Parcare")
        val venue = venue(listOf(behind, onTheWay, farOff, Poi("wc", patient.lat, patient.lon)))
        val target = Responders.target(incident(), me, venue, StraightLine)!!
        assertEquals("Postul medical", target.dea?.name)
        assertEquals(80, target.toDea)
        assertEquals(120, target.deaToPatient)
        assertEquals(200, target.direct)
        assertEquals(patient, target.patient)

        val nobody = Responders.target(incident(), null, venue, StraightLine)!!
        assertEquals("Lângă bar", nobody.dea?.name, "without the responder's position, the one nearest the patient")
        assertNull(nobody.toDea)
        assertNull(nobody.direct)
    }

    @Test
    fun theWalkingDistanceDecidesNotTheStraightLine() {
        val me = north(200)
        val onTheWay = Poi(Venue.DEA, north(120).lat, patient.lon, "Dincolo de gard")
        val behind = Poi(Venue.DEA, patient.lat - 60 / 111_320.0, patient.lon, "Lângă bar")
        // a fence: the one on the way can't be reached from here
        val fenced = WalkDistance { from, to -> if (from == me && to == onTheWay.point) null else distanceMeters(from, to) }
        val target = Responders.target(incident(), me, venue(listOf(onTheWay, behind)), fenced)!!
        assertEquals("Lângă bar", target.dea?.name)
        assertNull(Responders.target(incident(), me, venue(emptyList()), StraightLine)!!.dea, "no defibrillator on the map")
        assertEquals(
            venue(emptyList()).zone("main-stage")!!.center, Responders.target(incident(at = null), me, venue(emptyList()), StraightLine)!!.patient,
            "without the patient's position, the middle of the zone",
        )
    }

    @Test
    fun aResponderInRangeLeavesItOnlyPastTheMargin() {
        val edge = north(Responders.RANGE_M + Responders.LEAVE_MARGIN_M / 2)
        assertFalse(Responders.alerts(medic(edge), incident()), "a new incident: 300 m")
        assertTrue(Responders.alerts(medic(edge).copy(near = setOf("a")), incident()), "already in range: a few steps out don't end it")
        assertFalse(Responders.alerts(medic(north(Responders.RANGE_M + Responders.LEAVE_MARGIN_M + 20)).copy(near = setOf("a")), incident()))
    }

    private fun venue(pois: List<Poi>) = Venue(
        "Test", Bounds(43.99, 27.99, 44.01, 28.02),
        listOf(Zone("main-stage", "Scena", "#000000", listOf(GeoPoint(43.999, 27.999), GeoPoint(44.001, 27.999), GeoPoint(44.001, 28.001)))),
        pois = pois,
    )
}
