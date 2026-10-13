package ro.safetyplease.ui.map

import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.position.FriendPace
import ro.safetyplease.core.position.SeenAt
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.route.GuideEvent
import ro.safetyplease.core.venue.route.WalkGoal
import ro.safetyplease.ui.common.FriendSpot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavTargetTest {
    private val stage = Zone("stage", "Scena", "#336699", listOf(GeoPoint(44.0, 26.0), GeoPoint(44.0, 26.002), GeoPoint(44.002, 26.002), GeoPoint(44.002, 26.0)))
    private val venue = Venue(
        "test", Bounds(44.0, 26.0, 44.01, 26.01), listOf(stage),
        meetingPoint = GeoPoint(44.003, 26.003),
        pois = listOf(Poi("info", 44.004, 26.004, "Info", number = 14)),
    )
    private val friend = Friend(7, "Ana", "", "", 0)
    private val spot = FriendSpot(friend, GeoPoint(44.005, 26.005), 60_000, 10.0, FriendPace.MOVING)

    private fun goal(target: NavTarget, incidents: List<StaffIncident> = emptyList(), closed: Set<Int> = emptySet()) =
        target.goal(venue, listOf(spot), incidents, closed)

    @Test
    fun eachTargetLeadsToItsPlace() {
        assertEquals(WalkGoal.To(spot.point), goal(NavTarget.Friend(7)))
        assertEquals(WalkGoal.To(GeoPoint(44.003, 26.003)), goal(NavTarget.MeetingPoint))
        assertEquals(WalkGoal.To(GeoPoint(44.004, 26.004)), goal(NavTarget.Landmark(14)))
        assertEquals(WalkGoal.NearestOpen("water", setOf(3)), goal(NavTarget.Nearest("water"), closed = setOf(3)))
        assertEquals(WalkGoal.To(GeoPoint(44.006, 26.006)), goal(NavTarget.Place(GeoPoint(44.006, 26.006), "Cortul")))
        val incident = StaffIncident("i1", 1, 2, "stage", reportedAt = 0, receivedAt = 0, hops = 1)
        assertEquals(WalkGoal.To(stage.center), goal(NavTarget.Incident("i1"), listOf(incident)))
    }

    @Test
    fun aTargetWithoutAPlaceHasNoGoal() {
        assertNull(goal(NavTarget.Friend(8)))
        assertNull(goal(NavTarget.Landmark(15)))
        assertNull(goal(NavTarget.Incident("gone")))
        assertNull(NavTarget.MeetingPoint.goal(venue.copy(meetingPoint = null), emptyList(), emptyList(), emptySet()))
    }

    @Test
    fun aLastSeenTargetFollowsTheReportAndTheDefibrillatorLeadsOnToThePatient() {
        val incident = StaffIncident("i1", 1, 2, "stage", reportedAt = 0, receivedAt = 0, hops = 1)
        assertNull(goal(NavTarget.LastSeen("i1"), listOf(incident)))
        val seen = incident.copy(lastSeen = SeenAt(44.007, 26.007, 0, 3))
        assertEquals(WalkGoal.To(GeoPoint(44.007, 26.007)), goal(NavTarget.LastSeen("i1"), listOf(seen)))
        assertEquals(PinKind.LAST_SEEN, NavTarget.LastSeen("i1").pin)

        val dea = NavTarget.Defibrillator("i1", GeoPoint(44.008, 26.008), "DEA")
        assertEquals(WalkGoal.To(GeoPoint(44.008, 26.008)), goal(dea))
        assertEquals(NavTarget.Incident("i1"), dea.next)
        assertEquals(PinKind.SERVICE, dea.pin)
        assertNull(NavTarget.Incident("i1").next)
        assertNull(NavTarget.MeetingPoint.next)
    }

    @Test
    fun aLostSessionMeetsAtItsLandmarkAndAMapPlaceGoesByItsNumber() {
        assertEquals(NavTarget.Landmark(8), NavTarget.meeting(8))
        assertEquals(NavTarget.MeetingPoint, NavTarget.meeting(0))
        val point = GeoPoint(44.004, 26.004)
        assertEquals(NavTarget.Landmark(14), NavTarget.place(14, point, "Info"))
        assertEquals(NavTarget.Place(point, "Cortul"), NavTarget.place(0, point, "Cortul"))
    }

    @Test
    fun theArrowTurnsWithTheHeadingElseTheCourseAndIsNeverGuessed() {
        val from = GeoPoint(44.0, 26.0)
        val east = GeoPoint(44.0, 26.001)
        assertEquals(90f, routeArrowDeg(from, east, headingDeg = null, courseDeg = 0f)!!, 0.5f)
        assertEquals(0f, routeArrowDeg(from, east, headingDeg = 90f, courseDeg = 0f)!!, 0.5f)
        assertNull(routeArrowDeg(from, east, headingDeg = null, courseDeg = null))
    }

    @Test
    fun eachEventVibratesItsOwnWay() {
        val patterns = GuideEvent.entries.map { buzz(it).toList() }
        assertEquals(patterns.size, patterns.toSet().size)
        assertTrue(patterns.all { it.first() == 0L && it.size % 2 == 0 })
    }
}
