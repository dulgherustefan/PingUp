package ro.safetyplease.core.announce

import ro.safetyplease.core.venue.Venue

/** An evacuation in force: the plan the console named ([plan], null for none) and its zones (empty: the whole site). */
data class Evacuation(val plan: Int?, val zones: Set<String>) {
    fun covers(zoneId: String?): Boolean = zones.isEmpty() || zoneId in zones
}

/**
 * The map as the control console set it: the zones that aren't open, from the latest map-state notice, and the
 * evacuation in force, from the latest evacuation alert. Routing, the screens and the console read this one.
 */
data class MapState(val zones: Map<String, ZoneState> = emptyMap(), val evacuation: Evacuation? = null) {
    fun state(zoneId: String): ZoneState = zones[zoneId] ?: ZoneState.OPEN

    companion object {
        val OPEN = MapState()

        /** From the notices in force at [nowSec]; a map state made for another zone list is left out. */
        fun of(board: NoticeBoard, venue: Venue, nowSec: Long): MapState {
            val active = board.active(nowSec).map { it.second }
            val states = active.firstOrNull { it.topic == NoticeTopic.MAP_STATE }?.extras?.states
            val zones = if (states == null || states.size != venue.zones.size) {
                emptyMap()
            } else {
                venue.zones.zip(states).filter { it.second != ZoneState.OPEN }.associate { (zone, state) -> zone.id to state }
            }
            val alert = active.firstOrNull { it.topic == NoticeTopic.ALERT && it.level == NoticeLevel.EVACUATION }
            val evacuation = alert?.let { notice ->
                val targets = notice.extras.zones?.ids(venue) ?: setOfNotNull(notice.zone.takeIf { it.isNotEmpty() })
                Evacuation(notice.extras.plan, targets)
            }
            return MapState(zones, evacuation)
        }
    }
}
