package ro.safetyplease.core.announce

import ro.safetyplease.core.venue.EvacuationPlan
import ro.safetyplease.core.venue.EvacuationPlans
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue

/**
 * Where a phone in [zone] leaves in the evacuation in force: [exit], the zone's main exit in [plan], or its alternative
 * when the main one stands in a zone the console closed ([switched]). [fallback] is the other exit of the pair, while it
 * is still open. The walking routes take [point] as their destination.
 */
data class EvacuationTarget(val plan: EvacuationPlan, val zone: String, val exit: Poi, val fallback: Poi?, val switched: Boolean) {
    val point: GeoPoint get() = exit.point
}

/** The organiser's plans as the console starts them and as the phones follow them. */
object Evacuations {
    /**
     * The extensions of an evacuation alert naming [plan] (null: none) for [zones] (empty, or every zone: the whole site).
     * Null when the plan is not one [EvacuationPlans.usable] offers: a plan that fails the checks can't be started.
     */
    fun extras(venue: Venue, plan: Int?, zones: Set<String>): NoticeExtras? {
        if (plan != null && EvacuationPlans.usable(venue).none { it.id == plan }) return null
        return NoticeExtras(zones = ZoneSet.target(venue, zones), plan = plan)
    }

    /** The plan of the evacuation in force, when it names one this venue has. */
    fun plan(venue: Venue, state: MapState): EvacuationPlan? = state.evacuation?.plan?.let(venue::evacuationPlan)

    /** Where a phone in [zone] goes; null without an evacuation for that zone, without a plan, or for a zone the plan lacks. */
    fun target(venue: Venue, state: MapState, zone: String?): EvacuationTarget? {
        if (zone.isNullOrEmpty() || state.evacuation?.covers(zone) != true) return null
        val plan = plan(venue, state) ?: return null
        return target(venue, plan, state, zone)
    }

    fun target(venue: Venue, plan: EvacuationPlan, state: MapState, zone: String): EvacuationTarget? {
        val exits = plan.exits[zone] ?: return null
        val main = exitPoi(venue, exits.main) ?: return null
        val alternative = exitPoi(venue, exits.alternative)
        val mainClosed = closed(venue, state, main)
        return if (mainClosed && alternative != null && !closed(venue, state, alternative)) {
            EvacuationTarget(plan, zone, alternative, null, switched = true)
        } else {
            EvacuationTarget(plan, zone, main, alternative?.takeIf { !closed(venue, state, it) }, switched = false)
        }
    }

    /** Every exit the plan uses, by number: what a phone outside the zones or without a position is shown. */
    fun exits(venue: Venue, plan: EvacuationPlan?): List<Poi> {
        val all = venue.pois.filter { it.type == Venue.EXIT }
        val used = plan?.exits?.values?.flatMap { listOf(it.main, it.alternative) }?.toSet()
        return all.filter { used == null || it.number in used }.sortedBy { it.number }
    }

    private fun exitPoi(venue: Venue, number: Int): Poi? =
        if (number == 0) null else venue.pois.firstOrNull { it.type == Venue.EXIT && it.number == number }

    private fun closed(venue: Venue, state: MapState, exit: Poi): Boolean =
        venue.zoneAt(exit.lat, exit.lon)?.let { state.state(it.id) == ZoneState.CLOSED } == true
}
