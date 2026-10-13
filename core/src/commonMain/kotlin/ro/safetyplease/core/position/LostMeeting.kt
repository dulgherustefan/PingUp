package ro.safetyplease.core.position

import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance

/**
 * Where a group that lost one of its own meets: of the organiser's meeting landmarks, the one whose longest walk
 * from any of [people] is shortest, so nobody walks much more than the others. The middle of the points could fall in
 * the sea or behind a fence; a landmark is a place staff and lights are. Landmarks in a closed, exit-only or evacuated
 * zone are left out, and crowded ones are taken only when nothing else is open.
 */
object LostMeeting {
    /** Positions older than this say little of where the person is now. */
    const val FRESH_MS = 10 * 60_000L

    /** Null with no position, or when no landmark can be walked to from all of them. */
    fun choose(venue: Venue, people: List<GeoPoint>, walk: WalkDistance, state: MapState): Poi? {
        if (people.isEmpty()) return null
        val open = venue.meetingPlaces().filterNot { closed(venue, state, it) }
        val (crowded, calm) = open.partition { zoneState(venue, state, it) == ZoneState.CROWDED }
        return best(calm, people, walk) ?: best(crowded, people, walk)
    }

    private fun best(places: List<Poi>, people: List<GeoPoint>, walk: WalkDistance): Poi? = places.mapNotNull { place ->
        val longest = people.maxOf { walk.meters(it, place.point) ?: return@mapNotNull null }
        place to longest
    }.minWithOrNull(compareBy({ it.second }, { it.first.number }))?.first

    private fun zoneState(venue: Venue, state: MapState, place: Poi): ZoneState =
        venue.zoneAt(place.lat, place.lon)?.let { state.state(it.id) } ?: ZoneState.OPEN

    private fun closed(venue: Venue, state: MapState, place: Poi): Boolean {
        val zone = venue.zoneAt(place.lat, place.lon)?.id
        if (state.evacuation?.covers(zone) == true) return true
        return zoneState(venue, state, place).let { it == ZoneState.CLOSED || it == ZoneState.EXIT_ONLY }
    }
}
