package ro.safetyplease.core.control

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.Severity

/** A staff member's crowd level for a zone (roadmap row 7). [at] is the reporter's clock, [receivedAt] this phone's. */
@Serializable
data class DensityReading(
    val zone: String,
    val level: Int,
    val at: Long,
    val receivedAt: Long,
    val team: String = "",
    val incidentId: String = "",
)

/** What a phone knows of the zones' levels; [sentAt] is when it last sent one itself, on its own clock. */
@Serializable
data class DensityLog(val readings: List<DensityReading> = emptyList(), val sentAt: Long = 0)

/** Why a density report may not leave now; [ZONE_RECENT] is another level heard for the zone, [KNOWN] the same one. */
enum class DensityVerdict { SEND, PHONE_WAIT, ZONE_RECENT, KNOWN, RATE_LIMITED }

/** One step of a zone's timeline: from [at] (this phone's clock) the zone was at [level]. */
data class DensityStep(val zone: String, val at: Long, val level: Int)

/**
 * Staff's crowd levels in five pictures, sent as a staff report with the density entry (TLV 5) of the report body.
 * Levels 1 to 3 are routine: they go out as one packet that no phone acknowledges and that never opens an incident;
 * every staff phone keeps the zone's latest level instead. Level 4 opens a crowd incident and 5 an urgent one, through
 * the usual flow.
 *
 * The network's share stays at most [BUDGET_PER_S] by design: in each [zoneGapMs] a zone takes one report, plus the
 * rises above the highest level heard in that window that reach an incident (to 4 or 5, then 4 to 5), wherever they
 * come from: [PER_WINDOW] at most. Besides a rise, a phone sends at most one per [PHONE_GAP_MS].
 */
object StaffDensity {
    const val LEVELS = ReportExtras.MAX_DENSITY
    const val INCIDENT_LEVEL = 4
    const val URGENT_LEVEL = 5
    const val PHONE_GAP_MS = 2 * 60_000L

    /** The same level for a zone again only after this long: it says the zone still is so. */
    const val REFRESH_MS = 5 * 60_000L
    const val MIN_ZONE_GAP_MS = 60_000L
    const val BUDGET_PER_S = 0.1

    /** A zone's reports in one window at most: one, a rise to 4 or 5, and a rise from 4 to 5. */
    const val PER_WINDOW = 3

    /** Readings kept; a whole event at the budget is far fewer. */
    const val MAX_READINGS = 10_000

    /** [PER_WINDOW] reports a zone per window at most, over every zone, within the budget: 30 s × zones. */
    fun zoneGapMs(zones: Int): Long = maxOf(MIN_ZONE_GAP_MS, (PER_WINDOW * 1_000 * zones.coerceAtLeast(1) / BUDGET_PER_S).toLong())

    fun severity(level: Int): Int = when {
        level >= URGENT_LEVEL -> Severity.URGENT
        level >= INCIDENT_LEVEL -> Severity.MEDIUM
        else -> Severity.LOW
    }

    fun extras(level: Int) = ReportExtras(density = level)

    /** A level that only updates the zones' picture, never an incident. */
    fun isRoutine(category: Int, extras: ReportExtras): Boolean = category == IncidentCategory.CROWD && extras.density in 1 until INCIDENT_LEVEL

    fun isRoutine(incident: StaffIncident): Boolean = incident.category == IncidentCategory.CROWD && incident.density in 1 until INCIDENT_LEVEL

    fun reading(incident: StaffIncident) = DensityReading(
        incident.zone, incident.density, incident.reportedAt, incident.receivedAt, incident.nickname.orEmpty(), incident.incidentId,
    )

    /** When the level was seen: its reporter's time, so a reading that travelled long isn't fresh, but never after it arrived. */
    fun seenAt(r: DensityReading): Long = if (r.at > 0) minOf(r.at, r.receivedAt) else r.receivedAt

    /** Whether this phone may report [level] for [zone] now, from what it knows; [zones] is how many the venue has. */
    fun check(log: DensityLog, zone: String, level: Int, nowMs: Long, zones: Int): DensityVerdict {
        val heard = log.readings.filter { it.zone == zone && nowMs - seenAt(it) < zoneGapMs(zones) }
        val latest = latest(log.readings)[zone]
        // a zone getting worse reaches the console at once: above all it heard lately and above its latest level
        val rises = level >= INCIDENT_LEVEL && heard.all { it.level < level } && (latest == null || latest.level < level)
        if (rises) return DensityVerdict.SEND
        if (log.sentAt > 0 && nowMs - log.sentAt < PHONE_GAP_MS) return DensityVerdict.PHONE_WAIT
        if (heard.isNotEmpty()) return if (heard.maxBy(::seenAt).level == level) DensityVerdict.KNOWN else DensityVerdict.ZONE_RECENT
        if (latest != null && latest.level == level && nowMs - seenAt(latest) < REFRESH_MS) return DensityVerdict.KNOWN
        return DensityVerdict.SEND
    }

    /** The latest reading of each zone, by when it was seen. */
    fun latest(readings: List<DensityReading>): Map<String, DensityReading> =
        readings.filter { it.zone.isNotEmpty() }.groupBy { it.zone }.mapValues { (_, list) -> list.maxBy(::seenAt) }

    /** Each zone's changes of level within [from]..[until], zone by zone, oldest first. */
    fun timeline(readings: List<DensityReading>, from: Long, until: Long): List<DensityStep> =
        readings.filter { it.zone.isNotEmpty() && seenAt(it) in from..until }
            .sortedBy(::seenAt)
            .groupBy { it.zone }
            .entries.sortedBy { it.key }
            .flatMap { (zone, list) ->
                list.fold(emptyList<DensityStep>()) { steps, r ->
                    if (steps.lastOrNull()?.level == r.level) steps else steps + DensityStep(zone, seenAt(r), r.level)
                }
            }
}

/** The density readings this phone heard or sent, kept for the console's layer and the edition report. */
class DensityBoard(private val store: JsonStore<DensityLog>) {
    val state get() = store.state
    val value: DensityLog get() = store.value

    /** False if it was already known. */
    fun offer(reading: DensityReading): Boolean {
        if (reading.level !in 1..StaffDensity.LEVELS) return false
        if (reading.incidentId.isNotEmpty() && store.value.readings.any { it.incidentId == reading.incidentId }) return false
        store.update { it.copy(readings = (it.readings + reading).takeLast(StaffDensity.MAX_READINGS)) }
        return true
    }

    fun sent(atMs: Long) = store.update { it.copy(sentAt = atMs) }

    fun flush() = store.flush()
}
