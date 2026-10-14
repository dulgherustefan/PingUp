package ro.safetyplease.core.announce

import ro.safetyplease.core.venue.EvacuationPlan
import ro.safetyplease.core.venue.EvacuationPlans
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters

/**
 * Where a phone in [zone] leaves in the evacuation in force: [exit], the zone's main exit in [plan], or its alternative
 * when the main one stands in a zone the console closed ([switched]). [closed]: the exit given stands in a closed zone
 * too, since the plan had nothing open for this zone. [fallback] is the other exit of the pair, while it is still open.
 * The walking routes take [point] as their destination.
 */
data class EvacuationTarget(
    val plan: EvacuationPlan, val zone: String, val exit: Poi, val fallback: Poi?, val switched: Boolean, val closed: Boolean = false,
) {
    val point: GeoPoint get() = exit.point
}

/** An exit in the list a phone without an exit of its own is shown; [closed] when it stands in a closed zone. */
data class ExitChoice(val exit: Poi, val closed: Boolean)

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

    /**
     * What an evacuation the console sends while [inForce] hold goes out with: its zone set and zone field joined with
     * theirs, so a phone that keeps one evacuation, as earlier versions do, misses none; the whole site if any is for it.
     * Zones that can't be joined without the venue (another zone count, a zone named without a set) stay as given:
     * phones of this version join the evacuations in force on their own.
     */
    fun joined(extras: NoticeExtras, zone: String, inForce: List<Notice>): Pair<NoticeExtras, String> {
        if (inForce.isEmpty()) return extras to zone
        val all = listOf(extras.zones to zone) + inForce.map { it.extras.zones to it.zone }
        if (all.any { (set, field) -> set?.indices.isNullOrEmpty() && field.isEmpty() }) return extras.copy(zones = null) to ""
        val sets = all.mapNotNull { (set, _) -> set?.takeIf { it.indices.isNotEmpty() } }
        if (sets.size != all.size || sets.map { it.count }.distinct().size != 1) return extras to zone
        val indices = sets.flatMap { it.indices }.toSet()
        if (indices.size >= sets.first().count) return extras.copy(zones = null) to ""
        val field = if (indices.size == 1) all.map { it.second }.firstOrNull { it.isNotEmpty() }.orEmpty() else ""
        return extras.copy(zones = ZoneSet(sets.first().count, indices)) to field
    }

    /** The zones an evacuation for [zones] reaches while [state]'s is in force: both, or the whole site (empty) if either is. */
    fun joinedZones(state: MapState, zones: Set<String>): Set<String> {
        val current = state.evacuation ?: return zones
        return if (current.zones.isEmpty() || zones.isEmpty()) emptySet() else current.zones + zones
    }

    /** The plan of the evacuation in force, when it names one this venue has. */
    fun plan(venue: Venue, state: MapState): EvacuationPlan? = state.evacuation?.plan?.let(venue::evacuationPlan)

    /**
     * The zone whose evacuation this phone follows: its own when the evacuation covers it, else one its fix reaches that
     * the evacuation covers, else its own.
     */
    fun zoneFor(state: MapState, here: Whereabouts): String? {
        val evacuation = state.evacuation ?: return here.zone
        if (here.zone.isNullOrEmpty() || evacuation.covers(here.zone)) return here.zone
        return here.near.firstOrNull { evacuation.covers(it) } ?: here.zone
    }

    /** Where a phone in [zone] goes; null without an evacuation for that zone, without a plan, or for a zone the plan lacks. */
    fun target(venue: Venue, state: MapState, zone: String?): EvacuationTarget? {
        if (zone.isNullOrEmpty() || state.evacuation?.covers(zone) != true) return null
        val plan = plan(venue, state) ?: return null
        return target(venue, plan, state, zone)
    }

    private fun target(venue: Venue, plan: EvacuationPlan, state: MapState, zone: String): EvacuationTarget? {
        val exits = plan.exits[zone] ?: return null
        val main = exitPoi(venue, exits.main) ?: return null
        val alternative = exitPoi(venue, exits.alternative)
        val mainClosed = closed(venue, state, main)
        return when {
            !mainClosed -> EvacuationTarget(plan, zone, main, alternative?.takeIf { !closed(venue, state, it) }, switched = false)
            alternative != null && !closed(venue, state, alternative) -> EvacuationTarget(plan, zone, alternative, null, switched = true)
            else -> EvacuationTarget(plan, zone, main, null, switched = false, closed = true)
        }
    }

    /**
     * The exits a phone without one of its own is shown: those [plan] uses, or every exit of the venue without a plan.
     * The open ones first, nearest to [from] first when the phone knows where it is, else by number; then the closed ones.
     */
    fun exits(venue: Venue, plan: EvacuationPlan?, state: MapState = MapState.OPEN, from: GeoPoint? = null): List<ExitChoice> {
        val used = plan?.exits?.values?.flatMap { listOf(it.main, it.alternative) }?.toSet()
        return venue.pois.filter { it.type == Venue.EXIT && (used == null || it.number in used) }
            .map { ExitChoice(it, closed(venue, state, it)) }
            .sortedWith(compareBy<ExitChoice> { it.closed }.thenBy { c -> from?.let { distanceMeters(it, c.exit.point) } ?: 0 }.thenBy { it.exit.number })
    }

    private fun exitPoi(venue: Venue, number: Int): Poi? =
        if (number == 0) null else venue.pois.firstOrNull { it.type == Venue.EXIT && it.number == number }

    private fun closed(venue: Venue, state: MapState, exit: Poi): Boolean =
        venue.zoneAt(exit.lat, exit.lon)?.let { state.state(it.id) == ZoneState.CLOSED } == true
}
