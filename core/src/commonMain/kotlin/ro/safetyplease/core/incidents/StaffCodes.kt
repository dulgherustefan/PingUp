package ro.safetyplease.core.incidents

import ro.safetyplease.core.control.isAssignedTo
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity

/** The buttons of a field phone: one tap each, with nothing typed. */
enum class StaffCode { MEDIC, REINFORCEMENTS, STAFF_IN_DANGER, ON_SCENE, HANDED_OVER }

/**
 * Staff quick codes (roadmap 2.29). The first three leave as ordinary staff reports, so the console and the teams
 * treat them as any other; the last two are the field states of the incident the team already has, signed as ACKs.
 */
object StaffCodes {
    class Report(val category: Int, val severity: Int, val dangerContinues: Boolean)

    /** The report a code sends; null for the codes that move an incident instead. */
    fun report(code: StaffCode): Report? = when (code) {
        StaffCode.MEDIC -> Report(IncidentCategory.MEDICAL, Severity.URGENT, dangerContinues = false)
        StaffCode.REINFORCEMENTS -> Report(IncidentCategory.OTHER, Severity.URGENT, dangerContinues = false)
        StaffCode.STAFF_IN_DANGER -> Report(IncidentCategory.VIOLENCE, Severity.URGENT, dangerContinues = true)
        StaffCode.ON_SCENE, StaffCode.HANDED_OVER -> null
    }

    /**
     * The incident [team] holds that this code moves on: the one taken most recently. "La fața locului" fits a take
     * not yet on scene; "Predat la PMA" one that isn't handed over yet. An assignment the team hasn't accepted is not
     * theirs to move, as on the incident page.
     */
    fun target(code: StaffCode, incidents: List<StaffIncident>, team: String): StaffIncident? {
        val from = when (code) {
            StaffCode.ON_SCENE -> setOf(AckStatus.ACKNOWLEDGED)
            StaffCode.HANDED_OVER -> setOf(AckStatus.ACKNOWLEDGED, AckStatus.ON_SCENE)
            else -> return null
        }
        return incidents.filter { it.status in from && isAssignedTo(it.teamName, team) && !Routing.awaitsAcceptance(it, team) }
            .maxByOrNull { it.statusAt }
    }
}
