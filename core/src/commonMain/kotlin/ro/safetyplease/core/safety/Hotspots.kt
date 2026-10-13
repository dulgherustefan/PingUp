package ro.safetyplease.core.safety

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue

/**
 * One square of the site plan where harassment or "mă simt în nesiguranță" was reported in the last day: how many of
 * each, by hour ([perHour] has [Hotspots.HOURS] entries, the last one the hour that ends now) and when the latest
 * happened.
 */
data class Hotspot(
    val cell: String,
    val zoneName: String?,
    val harassment: Int,
    val unsafe: Int,
    val perHour: List<Int>,
    val lastAtMs: Long,
) {
    val total: Int get() = harassment + unsafe
}

/** [cells] busiest first; [unplaced] counts the reports with neither a position nor a zone to put them on the plan. */
data class HotspotMap(val cells: List<Hotspot>, val unplaced: Int)

/**
 * Where patrols should walk (roadmap 3.17): harassment and unsafe reports of the last 24 hours by square of the site
 * plan and by hour. A report counts when it happened, which is earlier than when it came in for a report written later.
 * For the console only; the public never sees the map.
 */
object Hotspots {
    const val HOURS = 24
    private const val HOUR_MS = 3_600_000L

    fun of(incidents: List<StaffIncident>, grid: SiteGrid, venue: Venue, nowMs: Long): HotspotMap {
        val counted = incidents.filter(::counts).mapNotNull { incident ->
            val at = incident.reportedAt - incident.happenedMinAgo * 60_000L
            // a clock a little ahead of ours still counts as now
            if (at <= nowMs - HOURS * HOUR_MS) null else incident to at.coerceAtMost(nowMs)
        }
        val byCell = LinkedHashMap<String, MutableList<Pair<StaffIncident, Long>>>()
        var unplaced = 0
        for ((incident, at) in counted) {
            val point = if (incident.lat != null && incident.lon != null) incident.lat to incident.lon else venue.zone(incident.zone)?.center?.let { it.lat to it.lon }
            val cell = point?.let { grid.cellAt(it.first, it.second) }
            if (cell == null) unplaced++ else byCell.getOrPut(cell) { mutableListOf() } += incident to at
        }
        val cells = byCell.map { (cell, reports) ->
            val perHour = IntArray(HOURS)
            for ((_, at) in reports) perHour[HOURS - 1 - ((nowMs - at) / HOUR_MS).toInt()]++
            val zone = reports.firstNotNullOfOrNull { (incident, _) -> venue.zone(incident.zone)?.name }
            Hotspot(
                cell, zone, reports.count { !Discreet.isDiscreet(it.first.description) }, reports.count { Discreet.isDiscreet(it.first.description) },
                perHour.toList(), reports.maxOf { it.second },
            )
        }
        return HotspotMap(cells.sortedWith(compareByDescending<Hotspot> { it.total }.thenByDescending { it.lastAtMs }), unplaced)
    }

    /** Harassment and the discreet request, unless it is "Nu sunt bine" or the report was withdrawn or found baseless. */
    private fun counts(incident: StaffIncident): Boolean =
        incident.category == IncidentCategory.HARASSMENT && !Welfare.isWelfare(incident.description) &&
            incident.status != AckStatus.CANCELLED && incident.status != AckStatus.UNFOUNDED
}
