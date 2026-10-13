package ro.safetyplease.core.position

import ro.safetyplease.core.announce.Evacuation
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The meeting landmark that keeps the longest walk shortest, and the zones it must avoid. */
class LostMeetingTest {
    private val origin = GeoPoint(44.0, 26.0)

    private fun at(east: Double, north: Double) = GeoPoint(
        origin.lat + north / 111_320.0,
        origin.lon + east / (111_320.0 * cos(radians(origin.lat))),
    )

    private fun square(id: String, east: Double, north: Double, size: Double) = Zone(
        id, id, "#888888", listOf(at(east, north), at(east + size, north), at(east + size, north + size), at(east, north + size)),
    )

    private fun landmark(number: Int, east: Double, north: Double) = at(east, north).let { Poi("info", it.lat, it.lon, number = number) }

    // three friends on a line north: 0, 100 and 300 m
    private val people = listOf(at(0.0, 0.0), at(0.0, 100.0), at(0.0, 300.0))

    private val venue = Venue(
        name = "Test",
        bounds = Bounds(origin.lat - 0.01, origin.lon - 0.01, origin.lat + 0.01, origin.lon + 0.01),
        zones = listOf(square("mid", -20.0, 130.0, 60.0), square("north", -20.0, 260.0, 40.0)),
        pois = listOf(
            // best by the longest walk: 150 m from both ends
            landmark(1, 0.0, 150.0),
            // nearest to the middle of the three (133 m north), but 180 m from the first
            landmark(2, 10.0, 180.0),
            // close to two of them, far from the third
            landmark(3, 0.0, 280.0),
        ),
    )

    @Test
    fun theLandmarkWithTheShortestLongestWalkWins() {
        assertEquals(1, LostMeeting.choose(venue, people, StraightLine, MapState.OPEN)?.number)
    }

    @Test
    fun aClosedZoneIsLeftOutEvenWhenItsLandmarkIsBest() {
        val closed = MapState(mapOf("mid" to ZoneState.CLOSED))
        // landmarks 1 and 2 sit in "mid": the fair one left is outside it
        val chosen = LostMeeting.choose(venue, people, StraightLine, closed)
        assertEquals(3, chosen?.number)
        val exitOnly = MapState(mapOf("mid" to ZoneState.EXIT_ONLY))
        assertEquals(3, LostMeeting.choose(venue, people, StraightLine, exitOnly)?.number)
    }

    @Test
    fun aCrowdedZoneOnlyWhenNothingCalmerIsOpen() {
        val crowded = MapState(mapOf("mid" to ZoneState.CROWDED))
        assertEquals(3, LostMeeting.choose(venue, people, StraightLine, crowded)?.number)
        val both = MapState(mapOf("mid" to ZoneState.CROWDED, "north" to ZoneState.CLOSED))
        assertEquals(1, LostMeeting.choose(venue, people, StraightLine, both)?.number)
    }

    @Test
    fun anEvacuatedZoneIsNoPlaceToMeet() {
        val evacuating = MapState(evacuation = Evacuation(1, setOf("mid")))
        assertEquals(3, LostMeeting.choose(venue, people, StraightLine, evacuating)?.number)
        val everywhere = MapState(evacuation = Evacuation(null, emptySet()))
        assertNull(LostMeeting.choose(venue, people, StraightLine, everywhere))
    }

    @Test
    fun aLandmarkSomeoneCannotWalkToIsNoChoice() {
        // a fence nobody can cross between the first friend and landmark 1
        val fenced = WalkDistance { from, to ->
            if (from == people[0] && to == venue.landmark(1)!!.point) null else distanceMeters(from, to)
        }
        assertEquals(2, LostMeeting.choose(venue, people, fenced, MapState.OPEN)?.number)
    }

    @Test
    fun theOrganisersChoiceOfMeetingLandmarksHolds() {
        val marked = venue.copy(meetingLandmarks = listOf(3))
        assertEquals(3, LostMeeting.choose(marked, people, StraightLine, MapState.OPEN)?.number)
    }

    @Test
    fun withNobodyKnownThereIsNoLandmark() {
        assertNull(LostMeeting.choose(venue, emptyList(), StraightLine, MapState.OPEN))
    }
}
