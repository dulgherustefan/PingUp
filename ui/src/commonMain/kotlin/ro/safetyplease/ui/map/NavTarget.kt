package ro.safetyplease.ui.map

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.position.Guide
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

    /** Where the person a lost report looks for was last seen, for staff; it follows the report's updates. */
    data class LastSeen(val incidentId: String) : NavTarget

    /** The defibrillator a responder fetches on the way to the patient of [incidentId]; the walk goes on from there. */
    data class Defibrillator(val incidentId: String, val point: GeoPoint, val name: String) : NavTarget

    /** Where the walk leads now; null when the target has no place, like a friend who shares nothing. */
    fun goal(venue: Venue, friends: List<FriendSpot>, incidents: List<StaffIncident>, closedPoints: Set<Int>): WalkGoal? = when (this) {
        is Friend -> friends.firstOrNull { it.friend.nodeId == nodeId }?.point?.let(WalkGoal::To)
        MeetingPoint -> venue.meetingPoint?.let(WalkGoal::To)
        is Landmark -> venue.landmark(number)?.point?.let(WalkGoal::To)
        is Nearest -> WalkGoal.NearestOpen(type, closedPoints)
        is Place -> WalkGoal.To(point)
        is Incident -> incidents.firstOrNull { it.incidentId == id }?.let { incidentPoint(venue, it) }?.let(WalkGoal::To)
        is LastSeen -> incidents.firstOrNull { it.incidentId == incidentId }?.lastSeen?.let { WalkGoal.To(GeoPoint(it.lat, it.lon)) }
        is Defibrillator -> WalkGoal.To(point)
    }

    /** The pin at the end of the route. */
    val pin: PinKind
        get() = when (this) {
            is Friend, is LastSeen -> PinKind.LAST_SEEN
            MeetingPoint, is Landmark -> PinKind.MEETING
            is Nearest, is Defibrillator -> PinKind.SERVICE
            is Place -> PinKind.SHARED_PLACE
            is Incident -> PinKind.INCIDENT
        }

    /** Where the walk goes on once this one is reached: from the defibrillator to the patient; null for the rest. */
    val next: NavTarget?
        get() = (this as? Defibrillator)?.let { Incident(it.incidentId) }

    companion object {
        /** Where a lost session meets: [landmark], or the venue's meeting point for 0. */
        fun meeting(landmark: Int): NavTarget = if (landmark != 0) Landmark(landmark) else MeetingPoint

        /** A place of the map by its landmark [number] when it has one, which the walk then names; else by its point. */
        fun place(number: Int, point: GeoPoint, label: String): NavTarget =
            if (number in Venue.LANDMARK_NUMBERS) Landmark(number) else Place(point, label)
    }
}

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
