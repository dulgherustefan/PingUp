package ro.safetyplease.ui.map

import ro.safetyplease.core.location.Fused
import ro.safetyplease.core.location.MeState
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.ui.common.Distance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The real map past the festival: where it opens, when it says it is only the overview, what it says of you. */
class MapAnywhereTest {
    private val now = 1_760_000_000_000L
    private val venue = Venue(
        name = "Test",
        bounds = Bounds(43.92, 28.59, 44.01, 28.675),
        zones = listOf(Zone("stage", "Scena", "#E5484D", listOf(GeoPoint(43.95, 28.63), GeoPoint(43.95, 28.64), GeoPoint(43.96, 28.635)))),
    )
    private val inStage = GeoPoint(43.953, 28.635)
    private val onSite = GeoPoint(43.93, 28.6)
    private val bucharest = GeoPoint(44.43, 26.10)
    private val festival = { p: GeoPoint -> venue.bounds.contains(p.lat, p.lon) }

    private fun fix(at: GeoPoint, ageMs: Long = 1_000, accuracy: Float = 5f) =
        Fused(GeoFix(at.lat, at.lon, accuracy, now - ageMs), PositionMethod.GNSS)

    private fun state(at: GeoPoint?, ageMs: Long = 1_000, accuracy: Float = 5f, locating: Boolean = true) =
        MeState.of(at?.let { fix(it, ageMs, accuracy) }, now, locating)

    @Test
    fun onSiteItOpensCloseOnYou() {
        val opening = MapAnywhere.opening(onSite, null, 16.5, festival)
        assertEquals(MapOpening(listOf(onSite), 16.5), opening)
    }

    @Test
    fun awayItOpensOnYouAtAZoomTheOverviewReadsAt() {
        assertEquals(MapOpening(listOf(bucharest), MapZoom.OVERVIEW_OPEN), MapAnywhere.opening(bucharest, null, 16.5, festival))
        val both = MapAnywhere.opening(bucharest, inStage, 16.5, festival)
        assertEquals(listOf(bucharest, inStage), both.points, "you and the place it was opened for")
        assertEquals(MapZoom.OVERVIEW_OPEN, both.maxZoom)
    }

    @Test
    fun withNoPositionItOpensOnTheFestival() {
        assertTrue(MapAnywhere.opening(null, null, 16.5, festival).points.isEmpty())
        assertEquals(listOf(inStage), MapAnywhere.opening(null, inStage, 16.5, festival).points)
    }

    @Test
    fun theOverviewNoteShowsPastZoomNineWhereNoPackHasDetail() {
        assertFalse(MapAnywhere.overviewOnly(MapZoom.OVERVIEW_MAX, detailed = false))
        assertTrue(MapAnywhere.overviewOnly(MapZoom.OVERVIEW_MAX + 0.5, detailed = false))
        assertFalse(MapAnywhere.overviewOnly(16.0, detailed = true))
        assertFalse(MapAnywhere.overviewOnly(4.0, detailed = false))
    }

    @Test
    fun theLabelSaysTheZoneTheGroundOrHowFarAway() {
        assertEquals(WhereText.InZone("Scena"), MapAnywhere.where(state(inStage), null, venue, "", now))
        assertEquals(WhereText.OnSite, MapAnywhere.where(state(onSite), null, venue, "", now))
        val away = MapAnywhere.where(state(bucharest), null, venue, "stage", now)
        assertIs<WhereText.Away>(away, "a real position wins over the zone picked by hand")
        assertEquals(208.0, away.meters / 1000.0, 2.0)
        assertEquals(WhereText.InZone("Scena"), MapAnywhere.where(state(null), inStage, venue, "", now), "the simulated position")
    }

    @Test
    fun anOldPositionSaysHowOld() {
        val old = state(onSite, ageMs = 7 * 60_000L)
        assertIs<MeState.Stale>(old)
        assertEquals(WhereText.Old(7), MapAnywhere.where(old, null, venue, "stage", now))
        val me = MapMe.of(old)!!
        assertTrue(me.stale)
        assertTrue(me.ringM > 7.9f, "grown with the age")
    }

    @Test
    fun aZonePickedByHandIsNeverShownAsYou() {
        val searching = state(null)
        assertEquals(WhereText.Picked("Scena"), MapAnywhere.where(searching, null, venue, "stage", now))
        assertNull(MapMe.of(searching), "no dot without the phone's position")
        assertEquals(WhereText.Searching, MapAnywhere.where(searching, null, venue, "", now))
        assertEquals(WhereText.Off, MapAnywhere.where(state(null, locating = false), null, venue, "", now))
    }

    @Test
    fun anUnknownAccuracyIsADashedFiftyMetreRing() {
        val me = MapMe.of(state(onSite, accuracy = 0f))!!
        assertTrue(me.dashed)
        assertEquals(50f, me.ringM)
        assertFalse(MapMe.of(state(onSite))!!.dashed)
    }

    @Test
    fun distancesReadInMetresThenKilometres() {
        assertEquals(Distance.Meters(950), Distance.of(950))
        assertEquals(Distance.Tenths(1, 2), Distance.of(1234))
        assertEquals(Distance.Km(10), Distance.of(9960))
        assertEquals(Distance.Km(213), Distance.of(212_700))
    }
}
