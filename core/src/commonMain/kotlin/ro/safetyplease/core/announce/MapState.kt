package ro.safetyplease.core.announce

import ro.safetyplease.core.venue.Venue

/** An evacuation in force: the plan the console named ([plan], null for none) and its zones (empty: the whole site). */
data class Evacuation(val plan: Int?, val zones: Set<String>) {
    fun covers(zoneId: String?): Boolean = zones.isEmpty() || zoneId in zones
}

/**
 * The map as the control console set it: the zones that aren't open, from the latest map-state notice, and the
 * evacuation in force, from every evacuation alert in force. Routing, the screens and the console read this one.
 */
data class MapState(val zones: Map<String, ZoneState> = emptyMap(), val evacuation: Evacuation? = null) {
    fun state(zoneId: String): ZoneState = zones[zoneId] ?: ZoneState.OPEN

    companion object {
        val OPEN = MapState()

        /**
         * From the notices in force at [nowSec]; a map state made for another zone list is left out. Evacuations stay
         * until withdrawn or ended, whatever else the console sends: their zones add up, the whole site if any is for
         * it, under the plan of the newest.
         */
        fun of(board: NoticeBoard, venue: Venue, nowSec: Long): MapState {
            val active = board.active(nowSec)
            val states = active.firstOrNull { it.second.topic == NoticeTopic.MAP_STATE }?.second?.extras?.states
            val zones = if (states == null || states.size != venue.zones.size) {
                emptyMap()
            } else {
                venue.zones.zip(states).filter { it.second != ZoneState.OPEN }.associate { (zone, state) -> zone.id to state }
            }
            val evacuations = active.filter { NoticeReach.isEvacuation(it.second) }
            val evacuation = evacuations.maxWithOrNull(compareBy({ it.first.orderOf(it.second) }, { it.second.seq }))?.let { (_, newest) ->
                val targets = evacuations.map { (_, notice) -> notice.extras.zones?.ids(venue) ?: setOfNotNull(notice.zone.takeIf { it.isNotEmpty() }) }
                Evacuation(newest.extras.plan, if (targets.any { it.isEmpty() }) emptySet() else targets.flatten().toSet())
            }
            return MapState(zones, evacuation)
        }
    }
}
