package ro.safetyplease.core.venue

/**
 * How far a person walks from one point of the site to another, in metres; null when no walk joins them (water, a fence
 * with no gate between, a closed zone). The walking routes implement it over the site's grid; until then, and
 * wherever they can't run, [StraightLine] stands in.
 */
fun interface WalkDistance {
    fun meters(from: GeoPoint, to: GeoPoint): Int?
}

/** As the crow flies: never null, and never longer than the walk. */
object StraightLine : WalkDistance {
    override fun meters(from: GeoPoint, to: GeoPoint): Int = distanceMeters(from, to)
}
