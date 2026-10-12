package ro.safetyplease.core.control

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.venue.SiteGrid

/** What the console saw or decided, in the order it happened. */
@Serializable
enum class LogEvent {
    /** The console opened the report. */
    REPORT,

    /** An ACK that became the incident's state on the network, signed here or by a team. */
    ACK,

    ASSIGN,
    TAKE,
    RESOLVE,

    /** Closed as a false alarm. The network only carries RESOLVED; the word and the reason stay in the log. */
    UNFOUNDED,

    /** Open again on the console only: the phones in the field keep the state they had. */
    REOPEN,

    /** The log was shared, usually at the end of a shift. */
    EXPORT,
    ;

    companion object {
        val DECISIONS = setOf(ASSIGN, TAKE, RESOLVE, UNFOUNDED, REOPEN)
    }
}

/**
 * One line of the console log; fields that don't apply to the [event] stay empty. [at] is this console's clock,
 * [reportedAt] the reporter's and [signedAt] the clock of the phone that signed the ACK.
 */
@Serializable
data class LogEntry(
    val at: Long,
    val event: LogEvent,
    val incidentId: String = "",
    val category: Int = 0,
    val severity: Int = 0,
    val zone: String = "",
    /** Square of the reported position on the site grid; empty without coordinates. */
    val grid: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val reportedAt: Long = 0,
    /** Hops the report or the ACK traveled; 0 for what this console signed. */
    val hops: Int = 0,
    val description: String = "",
    val status: Int = 0,
    /** The team named in the ACK, or the one the operator chose. */
    val team: String = "",
    val signedAt: Long = 0,
    /** Ed25519 signature of the ACK, in hex. */
    val signature: String = "",
    /** The ACK was signed on this console. */
    val own: Boolean = false,
    /** Why the operator decided so. */
    val reason: String = "",
)

@Serializable
data class ConsoleLog(val entries: List<LogEntry> = emptyList()) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** The report as the log keeps it; [at] is when this console opened it. */
fun reportEntry(incident: StaffIncident, grid: SiteGrid, at: Long = incident.receivedAt): LogEntry {
    val lat = incident.lat
    val lon = incident.lon
    return LogEntry(
        at, LogEvent.REPORT, incident.incidentId,
        category = incident.category, severity = incident.severity, zone = incident.zone,
        grid = if (lat != null && lon != null) grid.cellAt(lat, lon).orEmpty() else "",
        lat = lat, lon = lon, reportedAt = incident.reportedAt, hops = incident.hops, description = incident.description,
    )
}

/** The team named in an ACK is this phone's [team], or its role: "Medical" reaches "Medical 1" and "Medical 2". */
fun isAssignedTo(assigned: String, team: String): Boolean {
    val a = assigned.trim()
    val t = team.trim()
    if (a.isEmpty() || t.isEmpty()) return false
    return t.equals(a, ignoreCase = true) || t.startsWith("$a ", ignoreCase = true)
}
