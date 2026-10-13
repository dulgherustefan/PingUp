package ro.safetyplease.core.venue

/** What is wrong with an evacuation plan; [zone] names the zone it is about, if one. */
data class PlanProblem(val plan: Int, val kind: Kind, val zone: String = "") {
    enum class Kind {
        /** The id is outside 1..255, the one byte the console's evacuation names it by. */
        ID,
        DUPLICATE_ID,

        /** A zone of the venue has no exit in the plan. */
        ZONE_MISSING,

        /** The plan names a zone the venue doesn't have. */
        UNKNOWN_ZONE,

        /** An exit's number is not a useful point of type exit. */
        NOT_AN_EXIT,

        /** The alternative is the main exit again. */
        SAME_EXIT,

        /** Every zone leaves through the same exit: one blocked gate would trap the whole site. */
        SINGLE_EXIT,
    }
}

/** Checks the organiser's evacuation plans against the venue, before a console may name one. */
object EvacuationPlans {
    val IDS = 1..255

    fun problems(venue: Venue): List<PlanProblem> {
        val ids = venue.evacuation.groupingBy { it.id }.eachCount()
        return venue.evacuation.flatMap { plan ->
            problems(plan, venue) + listOfNotNull(PlanProblem(plan.id, PlanProblem.Kind.DUPLICATE_ID).takeIf { ids.getValue(plan.id) > 1 })
        }.distinct()
    }

    fun problems(plan: EvacuationPlan, venue: Venue): List<PlanProblem> {
        val found = mutableListOf<PlanProblem>()
        fun add(kind: PlanProblem.Kind, zone: String = "") = found.add(PlanProblem(plan.id, kind, zone))
        if (plan.id !in IDS) add(PlanProblem.Kind.ID)
        val exits = venue.pois.filter { it.type == Venue.EXIT && it.number in Venue.LANDMARK_NUMBERS }.map { it.number }.toSet()
        val zoneIds = venue.zones.map { it.id }.toSet()
        venue.zones.filter { it.id !in plan.exits }.forEach { add(PlanProblem.Kind.ZONE_MISSING, it.id) }
        for ((zone, exit) in plan.exits) {
            if (zone !in zoneIds) add(PlanProblem.Kind.UNKNOWN_ZONE, zone)
            if (exit.main !in exits || (exit.alternative != 0 && exit.alternative !in exits)) add(PlanProblem.Kind.NOT_AN_EXIT, zone)
            if (exit.alternative != 0 && exit.alternative == exit.main) add(PlanProblem.Kind.SAME_EXIT, zone)
        }
        val used = plan.exits.values.flatMap { listOf(it.main, it.alternative) }.filter { it != 0 }.toSet()
        if (used.size < 2) add(PlanProblem.Kind.SINGLE_EXIT)
        return found
    }

    /** The plans a console may start: those without a problem. */
    fun usable(venue: Venue): List<EvacuationPlan> {
        val bad = problems(venue).map { it.plan }.toSet()
        return venue.evacuation.filter { it.id !in bad }
    }
}
