package ro.safetyplease.core.incidents

import ro.safetyplease.core.control.isAssignedTo
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity

/**
 * Who a staff phone is, for routing: the control console, or a team in the field with its zone. A role lead (the
 * medical lead, the zone's security supervisor) is a field phone that rings like the console for its role.
 */
data class StaffProfile(
    val console: Boolean,
    val team: String = "",
    val zone: String = "",
    val lead: Boolean = false,
)

/** How a phone tells its holder, weakest first. */
enum class AlertLevel {
    /** On the list under "Toate", nothing else. */
    SILENT,
    VIBRATE,

    /** An ordinary notification, with the phone's sound. */
    NOTIFY,

    /** Its own channel, full screen once, the alarm sound again until someone has the incident. */
    INSISTENT,
}

enum class AlertReason {
    CONSOLE,
    LEAD,

    /** The console gave it to this team or its role, and the team hasn't accepted yet. */
    ASSIGNED,

    /** Urgent, in this phone's zone. */
    ZONE,

    /** Nobody took it within [Routing.WIDEN_AFTER_MS]: the incident's role hears it. */
    ROLE,

    /** Nobody took it within twice that: every staff phone hears it. */
    EVERYONE,

    /** Console only: the team it assigned hasn't accepted within [Routing.WIDEN_AFTER_MS]. */
    UNACCEPTED,
}

data class Route(val level: AlertLevel, val reason: AlertReason? = null) {
    companion object {
        val SILENT = Route(AlertLevel.SILENT)
    }
}

/**
 * Who gets what (roadmap 2.7). The insistent alert rings only on the console and on the leads of the incident's role;
 * field phones get what was assigned to them and urgencies in their zone, on vibration. While nobody has the incident,
 * it widens: after [WIDEN_AFTER_MS] to every phone of its role, after twice that to all staff. Every phone decides for
 * itself from the state it has; nothing here goes on the wire.
 */
object Routing {
    const val WIDEN_AFTER_MS = 90_000L

    /** The roles the console offers, first name canonical, then the names a team may carry in another language. */
    private val roles = listOf(
        listOf("Medical"),
        listOf("Securitate", "Security"),
        listOf("Stewarzi", "Stewards"),
        listOf("Pompieri", "Fire"),
    )

    /** The zone from the staff QR; without one, the zone the user picked. */
    fun profile(settings: Settings): StaffProfile = StaffProfile(
        console = settings.role == Role.CONTROL,
        team = settings.teamName,
        zone = settings.staffZone.ifBlank { settings.manualZone },
        lead = settings.roleLead,
    )

    /** The role that answers a category; null when any role may, as for "other" or a category from a newer version. */
    fun roleOf(category: Int): String? = when (category) {
        IncidentCategory.MEDICAL -> "Medical"
        IncidentCategory.VIOLENCE, IncidentCategory.HARASSMENT, IncidentCategory.LOST_CHILD -> "Securitate"
        IncidentCategory.CROWD -> "Stewarzi"
        IncidentCategory.FIRE -> "Pompieri"
        else -> null
    }

    /** "Medical 2" is in the role "Medical"; "Security 1" in "Securitate". */
    fun inRole(role: String, team: String): Boolean {
        val names = roles.firstOrNull { names -> names.any { it.equals(role.trim(), ignoreCase = true) } } ?: listOf(role)
        return names.any { isAssignedTo(it, team) }
    }

    /** A team has it or it is closed: the console, or someone, acted on it. */
    fun handled(incident: StaffIncident): Boolean = AckStatus.isTaken(incident.status) || AckStatus.isClosed(incident.status)

    /** The console gave it to [team] or its role, and nobody of the team has taken it as theirs yet. */
    fun awaitsAcceptance(incident: StaffIncident, team: String): Boolean =
        incident.assigned && incident.status == AckStatus.ACKNOWLEDGED && isAssignedTo(incident.teamName, team)

    /** Since when nobody has had it: since this phone opened the report, or since the console reopened it. */
    private fun waitingSince(incident: StaffIncident): Long =
        if (incident.status == AckStatus.REOPENED) incident.statusAt else incident.receivedAt

    /** 0 while it's fresh, 1 once its role hears it, 2 once everyone does; 0 when someone has it. */
    fun stage(incident: StaffIncident, nowMs: Long): Int {
        if (handled(incident)) return 0
        val waited = nowMs - waitingSince(incident)
        return when {
            waited >= 2 * WIDEN_AFTER_MS -> 2
            waited >= WIDEN_AFTER_MS -> 1
            else -> 0
        }
    }

    fun route(me: StaffProfile, incident: StaffIncident, nowMs: Long): Route {
        if (Priority.isInformational(incident.category) || AckStatus.isClosed(incident.status)) return Route.SILENT
        val urgent = incident.severity == Severity.URGENT
        if (me.console) {
            return when {
                !handled(incident) -> Route(if (urgent) AlertLevel.INSISTENT else AlertLevel.NOTIFY, AlertReason.CONSOLE)
                incident.assigned && incident.status == AckStatus.ACKNOWLEDGED && nowMs - incident.statusAt >= WIDEN_AFTER_MS ->
                    Route(AlertLevel.NOTIFY, AlertReason.UNACCEPTED)
                else -> Route.SILENT
            }
        }
        if (awaitsAcceptance(incident, me.team)) return Route(AlertLevel.VIBRATE, AlertReason.ASSIGNED)
        if (handled(incident)) return Route.SILENT
        val role = roleOf(incident.category)
        val ofMyRole = me.team.isNotBlank() && (role == null || inRole(role, me.team))
        val zone = incident.zone.trim()
        val inMyZone = me.zone.isNotBlank() && zone.equals(me.zone.trim(), ignoreCase = true)
        // a lead with a zone answers for that zone, and for reports that name none
        if (me.lead && ofMyRole && (me.zone.isBlank() || zone.isEmpty() || inMyZone)) {
            return Route(if (urgent) AlertLevel.INSISTENT else AlertLevel.NOTIFY, AlertReason.LEAD)
        }
        // a phone that knows neither its team nor its zone can't be routed to: it hears every urgency
        val unrouted = me.team.isBlank() && me.zone.isBlank()
        val stage = stage(incident, nowMs)
        return when {
            urgent && (inMyZone || unrouted) -> Route(AlertLevel.VIBRATE, AlertReason.ZONE)
            stage >= 2 -> Route(AlertLevel.VIBRATE, AlertReason.EVERYONE)
            stage >= 1 && role != null && ofMyRole -> Route(AlertLevel.VIBRATE, AlertReason.ROLE)
            else -> Route.SILENT
        }
    }

    /** On the field phone's default list: what is routed to it, and what its team already has. */
    fun isForMe(me: StaffProfile, incident: StaffIncident, nowMs: Long): Boolean =
        route(me, incident, nowMs).level != AlertLevel.SILENT ||
            (AckStatus.isTaken(incident.status) && isAssignedTo(incident.teamName, me.team))

    /** The next moment the route may change by time alone: a widening, or an assignment going unanswered. */
    fun nextChange(me: StaffProfile, incident: StaffIncident, nowMs: Long): Long? {
        if (AckStatus.isClosed(incident.status)) return null
        val moments = when {
            me.console -> if (incident.assigned && incident.status == AckStatus.ACKNOWLEDGED) listOf(incident.statusAt + WIDEN_AFTER_MS) else emptyList()
            handled(incident) -> emptyList()
            else -> waitingSince(incident).let { listOf(it + WIDEN_AFTER_MS, it + 2 * WIDEN_AFTER_MS) }
        }
        return moments.filter { it > nowMs }.minOrNull()
    }
}
