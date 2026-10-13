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

    /** The operator closed the incident. */
    RESOLVE,

    /** Closed as a false alarm. */
    UNFOUNDED,

    /** Open again, in the next epoch on the network. */
    REOPEN,

    /** The log was shared, usually at the end of a shift. */
    EXPORT,

    /** The reporter cancelled a report the operator has to check: someone may still need help. */
    CANCEL_REQUEST,

    /** The operator checked the reporter's cancel and confirmed it. */
    CANCEL,

    /** A notice to every phone, sent here or by another console; the incident fields stay empty. */
    NOTICE,

    /** A notice withdrawn. */
    NOTICE_CANCEL,
    ;

    companion object {
        val DECISIONS = setOf(ASSIGN, TAKE, RESOLVE, UNFOUNDED, REOPEN, CANCEL)
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
    /** The epoch of the ACK, or the one a cancel request came in. */
    val epoch: Int = 0,
    /** The team named in the ACK, the one the operator chose, or the one that wrote a staff report. */
    val team: String = "",
    val signedAt: Long = 0,
    /** Ed25519 signature of the ACK, in hex. */
    val signature: String = "",
    /** The ACK was signed on this console. */
    val own: Boolean = false,
    /** Why the operator decided so. */
    val reason: String = "",
    /** Triage answers of a medical report, as [ro.safetyplease.core.protocol.Answer] codes. */
    val responds: Int = 0,
    val breathes: Int = 0,
    /** A report written by staff, with its ETHANE-lite fields. */
    val fromStaff: Boolean = false,
    val people: Int = 0,
    val dangerContinues: Boolean = false,
    /** How far off the reported position may be, in metres; 0 when unknown. */
    val accuracyM: Int = 0,
    /** Number of the landmark nearest to the reporter, on the site plan; 0 for none. */
    val landmark: Int = 0,
    /** A notice's topic, level, template and sequence number, and when it stops holding (ms); its text is the description. */
    val topic: Int = 0,
    val level: Int = 0,
    val template: Int = 0,
    val seq: Long = 0,
    val validUntil: Long = 0,
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
        team = if (incident.fromStaff) incident.nickname.orEmpty() else "",
        responds = incident.responds, breathes = incident.breathes,
        fromStaff = incident.fromStaff, people = incident.people, dangerContinues = incident.dangerContinues,
        accuracyM = incident.accuracyM, landmark = incident.landmark,
    )
}

/** The team named in an ACK is this phone's [team], or its role: "Medical" reaches "Medical 1" and "Medical 2". */
fun isAssignedTo(assigned: String, team: String): Boolean {
    val a = assigned.trim()
    val t = team.trim()
    if (a.isEmpty() || t.isEmpty()) return false
    return t.equals(a, ignoreCase = true) || t.startsWith("$a ", ignoreCase = true)
}
