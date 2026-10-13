package ro.safetyplease.core.venue

import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.control.PointReport
import ro.safetyplease.core.protocol.PointLevel

/** How a service point is: as staff last said, or [UNKNOWN] when nobody said or nobody confirmed it lately. */
enum class ServiceState { FREE, BUSY, CLOSED, UNKNOWN }

/** One place of a service layer: a POI, or the middle of a zone of that type (a medical tent drawn as a zone). */
data class ServicePoint(val type: String, val name: String, val point: GeoPoint, val number: Int, val state: ServiceState) {
    /** What a tap on its pin names; numbered points by number, the rest by type and position in the venue. */
    val key: String get() = "svc:$type:${if (number > 0) number.toString() else "${point.lat},${point.lon}"}"

    val spot: Spot get() = Spot(type, name, point)
}

/**
 * The service layers of the map: the venue's places of each service type, with the live status staff report for the
 * numbered ones ([PointBoard]), where this phone has it.
 */
object ServiceLayer {
    /** In the order of the filter chips. */
    val TYPES = listOf(
        "water", "wc", "medical", Venue.DEA, "info", "charge", Venue.SHADE, SAFE, PHONE, Venue.EXIT, LOCKERS,
    )

    /** A safe space (Safe Zone, welfare). */
    const val SAFE = "safe"

    /** A landline phone. */
    const val PHONE = "phone"
    const val LOCKERS = "lockers"

    /** The layers this venue has places for. */
    fun present(venue: Venue): List<String> = TYPES.filter { venue.spots(it).isNotEmpty() }

    fun state(report: PointReport?, nowMs: Long): ServiceState = when {
        report == null || PointBoard.isStale(report, nowMs) -> ServiceState.UNKNOWN
        report.level == PointLevel.FREE -> ServiceState.FREE
        report.level == PointLevel.BUSY -> ServiceState.BUSY
        else -> ServiceState.CLOSED
    }

    /** The places of [types], each with its status; [openOnly] leaves out those staff said are closed. */
    fun points(venue: Venue, types: Set<String>, board: Map<Int, PointReport>, nowMs: Long, openOnly: Boolean = false): List<ServicePoint> =
        TYPES.filter { it in types }.flatMap { type ->
            venue.zones.filter { it.type == type }.map { ServicePoint(type, it.name, it.center, 0, ServiceState.UNKNOWN) } +
                venue.pois.filter { it.type == type }.map { poi ->
                    val state = if (poi.number in Venue.LANDMARK_NUMBERS) state(board[poi.number], nowMs) else ServiceState.UNKNOWN
                    ServicePoint(type, poi.name, poi.point, poi.number, state)
                }
        }.filter { !openOnly || it.state != ServiceState.CLOSED }

    /** The nearest place of [type] not said to be closed, by the walk from [from]; one no walk reaches doesn't count. */
    fun nearestOpen(venue: Venue, type: String, board: Map<Int, PointReport>, nowMs: Long, from: GeoPoint, walk: WalkDistance): ServicePoint? =
        points(venue, setOf(type), board, nowMs, openOnly = true)
            .mapNotNull { p -> walk.meters(from, p.point)?.let { p to it } }
            .minByOrNull { it.second }?.first
}
