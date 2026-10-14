package ro.safetyplease.core.protocol

import ro.safetyplease.core.util.validLatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A place off the earth, from a buggy phone or made to crash the map that moves to it, counts as no place as it is read:
 * the message keeps the rest, or, when the place is all it says, isn't read at all.
 */
class CoordinateRangeTest {
    private val offEarth = listOf(1000.0 to 26.0, -90.5 to 26.0, 44.0 to 180.5, 44.0 to -200.0)

    @Test
    fun theEarthEndsAtNinetyAndOneHundredEightyDegrees() {
        assertTrue(validLatLon(90.0, 180.0) && validLatLon(-90.0, -180.0) && validLatLon(44.4268, 26.1025))
        assertFalse(validLatLon(90.000001, 0.0))
        assertFalse(validLatLon(0.0, -180.000001))
        assertFalse(validLatLon(Double.NaN, 0.0))
    }

    @Test
    fun aReportOrAnUpdateOffTheEarthKeepsEverythingButThePlace() {
        for ((lat, lon) in offEarth) {
            val report = IncidentBody.decode(IncidentBody(IncidentCategory.MEDICAL, Severity.URGENT, 1L, "scena", lat, lon, "x", "Ana").encode())!!
            assertNull(report.lat)
            assertNull(report.lon)
            assertEquals("scena" to "Ana", report.zone to report.nickname)
            val update = UpdateBody.decode(UpdateBody(UpdateKind.MOVED, 1L, ByteArray(IncidentCancel.TOKEN_SIZE), "bar", lat, lon).encode())!!
            assertNull(update.lat)
            assertEquals("bar", update.zone)
        }
        val kept = IncidentBody.decode(IncidentBody(IncidentCategory.MEDICAL, Severity.URGENT, 1L, "", -89.999999, 179.999999, "x", null).encode())!!
        assertEquals(-89.999999 to 179.999999, kept.lat to kept.lon)
    }

    @Test
    fun aZoneMessageKeepsItsZoneWithoutThePlace() {
        val zone = InnerCodec.decode(InnerCodec.encode(Inner.Zone(1L, null, "scena", 1000.0, 26.0))) as Inner.Zone
        assertEquals("scena", zone.zone)
        assertNull(zone.lat)
        assertNull(zone.lon)
    }

    @Test
    fun aPositionAMeetingPointOrALastGaspOffTheEarthIsNotRead() {
        for ((lat, lon) in offEarth) {
            val position = Inner.Position(PositionPoint.Absolute(lat, lon), 0, PositionMethod.GNSS, 1, 2, 3, Heading.UNKNOWN)
            assertNull(InnerCodec.decode(InnerCodec.encode(position)))
            assertNull(InnerCodec.decode(InnerCodec.encode(Inner.MeetingPoint(1L, null, 1, 0, lat, lon, false, null, 30))))
            assertNull(InnerCodec.decode(InnerCodec.encode(Inner.LastGasp(LastFix(lat, lon, 1L, 0), 5))))
        }
        assertNotNull(InnerCodec.decode(InnerCodec.encode(Inner.LastGasp(LastFix(44.0, 26.0, 1L, 0), 5))))
    }

    @Test
    fun aMissedCheckinStillComesWithoutItsPlace() {
        val missed = InnerCodec.decode(InnerCodec.encode(Inner.CheckinMissed(1L, null, 100L, LastFix(1000.0, 26.0, 1L, 0), "Gara"))) as Inner.CheckinMissed
        assertEquals("Gara", missed.label)
        assertNull(missed.lastFix)
    }

    @Test
    fun aLastSeenPlaceOffTheEarthIsLeftOutOfTheReportsExtras() {
        val extras = ReportExtras.decode(ReportExtras(lastSeen = LastSeen(95.0, 26.0, 10, 2), witness = true).encode())
        assertNull(extras.lastSeen)
        assertTrue(extras.witness)
        assertEquals(LastSeen(44.5, 26.1, 10, 2), ReportExtras.decode(ReportExtras(lastSeen = LastSeen(44.5, 26.1, 10, 2)).encode()).lastSeen)
    }
}
