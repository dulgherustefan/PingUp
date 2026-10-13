package ro.safetyplease.ui.map

import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.control.PointReport
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.position.Guide
import ro.safetyplease.core.protocol.PointLevel
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.route.GuideEvent
import ro.safetyplease.core.venue.route.WalkGoal
import ro.safetyplease.ui.common.FriendSpot
import ro.safetyplease.ui.common.incidentPoint

/** Where "Du-mă acolo" leads; `vm.navigate(target)` opens the walk there. */
sealed interface NavTarget {
    /** A friend's last shared position, followed as it moves. */
    data class Friend(val nodeId: Long) : NavTarget

    /** The venue's meeting point. */
    data object MeetingPoint : NavTarget

    /** A numbered landmark of the site plan. */
    data class Landmark(val number: Int) : NavTarget

    /** The open place of [type] nearest by walking: water, wc, medical, exit, charge, dea... */
    data class Nearest(val type: String) : NavTarget

    /** A point with a name: a saved place, or one sent in a message. */
    data class Place(val point: GeoPoint, val label: String) : NavTarget

    /** An open incident, for staff. */
    data class Incident(val id: String) : NavTarget

    /** Where the walk leads now; null when the target has no place, like a friend who shares nothing. */
    fun goal(venue: Venue, friends: List<FriendSpot>, incidents: List<StaffIncident>, closedPoints: Set<Int>): WalkGoal? = when (this) {
        is Friend -> friends.firstOrNull { it.friend.nodeId == nodeId }?.point?.let(WalkGoal::To)
        MeetingPoint -> venue.meetingPoint?.let(WalkGoal::To)
        is Landmark -> venue.landmark(number)?.point?.let(WalkGoal::To)
        is Nearest -> WalkGoal.NearestOpen(type, closedPoints)
        is Place -> WalkGoal.To(point)
        is Incident -> incidents.firstOrNull { it.incidentId == id }?.let { incidentPoint(venue, it) }?.let(WalkGoal::To)
    }

    /** The pin at the end of the route. */
    val pin: PinKind
        get() = when (this) {
            is Friend -> PinKind.LAST_SEEN
            MeetingPoint, is Landmark -> PinKind.MEETING
            is Nearest -> PinKind.SERVICE
            is Place -> PinKind.SHARED_PLACE
            is Incident -> PinKind.INCIDENT
        }
}

/** The numbered points staff marked closed or out of service and nobody contradicted since. */
fun closedPoints(points: Map<Int, PointReport>, nowMs: Long): Set<Int> =
    points.values.filter { it.level == PointLevel.CLOSED && !PointBoard.isStale(it, nowMs) }.map { it.point }.toSet()

/**
 * The arrow's angle on screen, clockwise, towards [toward]: from the way the phone faces, else from the way the person
 * walks; null with neither, since a north guessed would point wrong.
 */
fun routeArrowDeg(from: GeoPoint, toward: GeoPoint, headingDeg: Float?, courseDeg: Float?): Float? {
    val reference = headingDeg ?: courseDeg ?: return null
    return Guide.arrowDeg(Guide.bearing(from, toward), reference)
}

/** A vibration for each event, told apart without looking: two short for a turn, three when off the route, one long on arrival. */
fun buzz(event: GuideEvent): LongArray = when (event) {
    GuideEvent.TURN_SOON -> longArrayOf(0, 120, 120, 120)
    GuideEvent.OFF_ROUTE -> longArrayOf(0, 120, 120, 120, 120, 120)
    GuideEvent.ARRIVED -> longArrayOf(0, 600)
}
