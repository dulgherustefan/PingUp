package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Routing.WIDEN_AFTER_MS
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutingTest {
    private val console = StaffProfile(console = true, team = "Control")
    private val medic = StaffProfile(console = false, team = "Medical 2", zone = "camping")
    private val guard = StaffProfile(console = false, team = "Securitate 1", zone = "main-stage")
    private val medicalLead = StaffProfile(console = false, team = "Medical 1", lead = true)

    private fun incident(
        severity: Int = Severity.URGENT,
        category: Int = IncidentCategory.MEDICAL,
        zone: String = "main-stage",
        status: Int = AckStatus.RECEIVED,
        team: String = "",
        assigned: Boolean = false,
        receivedAt: Long = 0,
        statusAt: Long = 0,
        epoch: Int = 0,
        id: String = "a",
    ) = StaffIncident(
        incidentId = id, category = category, severity = severity, zone = zone, reportedAt = receivedAt, receivedAt = receivedAt,
        hops = 1, status = status, teamName = team, assigned = assigned, statusAt = statusAt, epoch = epoch,
    )

    private fun level(me: StaffProfile, incident: StaffIncident, now: Long = 0) = Routing.route(me, incident, now).level

    @Test
    fun theInsistentAlertRingsOnlyOnTheConsoleAndTheLeadsOfTheRole() {
        val urgent = incident()
        assertEquals(Route(AlertLevel.INSISTENT, AlertReason.CONSOLE), Routing.route(console, urgent, 0))
        assertEquals(Route(AlertLevel.INSISTENT, AlertReason.LEAD), Routing.route(medicalLead, urgent, 0))
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.ZONE), Routing.route(guard, urgent, 0), "a guard of the zone feels it, doesn't hear it")
        assertEquals(AlertLevel.SILENT, level(medic, urgent), "a medic elsewhere hears nothing yet")
        assertEquals(
            AlertLevel.SILENT,
            level(medicalLead, incident(category = IncidentCategory.VIOLENCE, zone = "camping")),
            "the medical lead doesn't ring for a fight",
        )
        assertEquals(AlertLevel.INSISTENT, level(medicalLead, incident(category = IncidentCategory.OTHER)), "any lead may answer \"other\"")
    }

    @Test
    fun aLeadWithAZoneAnswersForThatZoneAndForReportsWithoutOne() {
        val zoneLead = guard.copy(lead = true)
        val fight = incident(category = IncidentCategory.VIOLENCE)
        assertEquals(AlertLevel.INSISTENT, level(zoneLead, fight))
        assertEquals(AlertLevel.INSISTENT, level(zoneLead, fight.copy(zone = "")))
        assertEquals(AlertLevel.SILENT, level(zoneLead, fight.copy(zone = "camping")))
    }

    @Test
    fun whatIsNotUrgentIsAnOrdinaryNotificationOnTheConsoleAndQuietInTheField() {
        val medium = incident(severity = Severity.MEDIUM)
        assertEquals(AlertLevel.NOTIFY, level(console, medium))
        assertEquals(AlertLevel.NOTIFY, level(medicalLead, medium))
        assertEquals(AlertLevel.SILENT, level(guard, medium), "not urgent: the zone doesn't hear it")
    }

    @Test
    fun anAssignmentReachesTheTeamAndItsRoleOnVibrationUntilTheyAcceptIt() {
        val assigned = incident(severity = Severity.MEDIUM, zone = "camping", status = AckStatus.ACKNOWLEDGED, team = "Medical", assigned = true)
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.ASSIGNED), Routing.route(medic, assigned, 0))
        assertTrue(Routing.awaitsAcceptance(assigned, "Medical 2"))
        assertEquals(AlertLevel.SILENT, level(guard, assigned), "another role")
        assertEquals(AlertLevel.SILENT, level(console, assigned), "the console acted: nothing rings there")
        val accepted = assigned.copy(assigned = false, teamName = "Medical 2")
        assertEquals(AlertLevel.SILENT, level(medic, accepted), "accepted: it's the team's work, nothing left to ring")
        assertTrue(Routing.isForMe(medic, accepted, 0), "but it stays on the team's list")
        assertFalse(Routing.isForMe(guard, accepted, 0))
    }

    @Test
    fun theConsoleHearsOfAnAssignmentNobodyAccepted() {
        val assigned = incident(status = AckStatus.ACKNOWLEDGED, team = "Medical 2", assigned = true, statusAt = 1_000)
        assertEquals(AlertLevel.SILENT, level(console, assigned, 1_000 + WIDEN_AFTER_MS - 1))
        assertEquals(Route(AlertLevel.NOTIFY, AlertReason.UNACCEPTED), Routing.route(console, assigned, 1_000 + WIDEN_AFTER_MS))
        assertEquals(1_000 + WIDEN_AFTER_MS, Routing.nextChange(console, assigned, 1_000))
        assertNull(Routing.nextChange(medic, assigned, 1_000))
        assertEquals(AlertLevel.SILENT, level(console, assigned.copy(assigned = false), 1_000 + WIDEN_AFTER_MS))
    }

    @Test
    fun withoutTheConsoleTheAlertWidensToTheRoleThenToEveryone() {
        val urgent = incident(zone = "bar", receivedAt = 10_000)
        assertEquals(AlertLevel.SILENT, level(medic, urgent, 10_000 + WIDEN_AFTER_MS - 1))
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.ROLE), Routing.route(medic, urgent, 10_000 + WIDEN_AFTER_MS))
        assertEquals(AlertLevel.SILENT, level(guard, urgent, 10_000 + WIDEN_AFTER_MS), "another role waits longer")
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.EVERYONE), Routing.route(guard, urgent, 10_000 + 2 * WIDEN_AFTER_MS))
        assertEquals(10_000 + WIDEN_AFTER_MS, Routing.nextChange(guard, urgent, 10_000))
        assertEquals(10_000 + 2 * WIDEN_AFTER_MS, Routing.nextChange(guard, urgent, 10_000 + WIDEN_AFTER_MS))
        assertNull(Routing.nextChange(guard, urgent, 10_000 + 2 * WIDEN_AFTER_MS))
        val taken = urgent.copy(status = AckStatus.ACKNOWLEDGED, teamName = "Medical 1")
        assertEquals(AlertLevel.SILENT, level(guard, taken, 10_000 + 2 * WIDEN_AFTER_MS), "someone has it: it stops widening")
        assertNull(Routing.nextChange(guard, taken, 10_000))
        val other = incident(category = IncidentCategory.OTHER, zone = "bar")
        assertEquals(AlertLevel.SILENT, level(medic, other, WIDEN_AFTER_MS), "no role answers \"other\" by itself")
        assertEquals(AlertLevel.VIBRATE, level(medic, other, 2 * WIDEN_AFTER_MS))
    }

    @Test
    fun aReopenStartsTheWaitAgain() {
        val reopened = incident(zone = "bar", status = AckStatus.REOPENED, statusAt = 500_000, epoch = 1)
        assertEquals(AlertLevel.SILENT, level(guard, reopened, 500_000 + WIDEN_AFTER_MS))
        assertEquals(AlertLevel.VIBRATE, level(guard, reopened, 500_000 + 2 * WIDEN_AFTER_MS))
        assertEquals(AlertLevel.INSISTENT, level(console, reopened, 500_000))
    }

    @Test
    fun closedAndInformationalIncidentsNeverAlert() {
        val closed = incident(status = AckStatus.CLOSED_BY_CONTROL)
        val lostFriend = incident(category = IncidentCategory.LOST_FRIEND, severity = Severity.LOW)
        for (me in listOf(console, medicalLead, guard)) {
            assertEquals(AlertLevel.SILENT, level(me, closed, 10 * WIDEN_AFTER_MS))
            assertEquals(AlertLevel.SILENT, level(me, lostFriend, 10 * WIDEN_AFTER_MS))
        }
        assertNull(Routing.nextChange(guard, closed, 0))
    }

    @Test
    fun aPhoneThatKnowsNeitherTeamNorZoneFeelsEveryUrgency() {
        val bare = StaffProfile(console = false)
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.ZONE), Routing.route(bare, incident(zone = "anywhere"), 0))
        assertEquals(AlertLevel.SILENT, level(bare, incident(severity = Severity.MEDIUM)))
    }

    @Test
    fun rolesMatchTheConsolesNamesInEitherLanguage() {
        assertTrue(Routing.inRole("Securitate", "Security 3"))
        assertTrue(Routing.inRole("Medical", "medical 2"))
        assertFalse(Routing.inRole("Medical", "Medicale"))
        assertTrue(Routing.inRole("Welfare", "Welfare 1"), "a role the list doesn't know matches by name")
        assertEquals("Securitate", Routing.roleOf(IncidentCategory.LOST_CHILD))
        assertNull(Routing.roleOf(42))
    }

    @Test
    fun theProfileComesFromTheSettings() {
        val settings = Settings(role = Role.STAFF, teamName = "Medical 2", manualZone = "bar", roleLead = true)
        assertEquals(StaffProfile(console = false, team = "Medical 2", zone = "bar", lead = true), Routing.profile(settings))
        assertEquals("camping", Routing.profile(settings.copy(staffZone = "camping")).zone)
        assertTrue(Routing.profile(settings.copy(role = Role.CONTROL)).console)
    }

    @Test
    fun aDiscreetReportGoesToSafeguardingWhateverItsCategory() {
        val discreet = incident(severity = Severity.MEDIUM, category = IncidentCategory.HARASSMENT).copy(description = Discreet.describe("Mă simt în nesiguranță"))
        assertEquals(Discreet.ROLE, Routing.roleOf(discreet))
        assertEquals("Securitate", Routing.roleOf(discreet.copy(description = "cineva mă urmărește")))
        assertTrue(Routing.inRole(Discreet.ROLE, "Safeguarding 1"))
        assertTrue(Routing.inRole(Discreet.ROLE, "Protecție"))
    }

    @Test
    fun safeguardingFeelsADiscreetReportAtOnceAndNobodyElseHearsItEarly() {
        val discreet = incident(severity = Severity.MEDIUM, category = IncidentCategory.HARASSMENT).copy(description = Discreet.describe("x"))
        val safeguarding = StaffProfile(console = false, team = "Safeguarding 1", zone = "camping")
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.ROLE), Routing.route(safeguarding, discreet, 0))
        assertEquals(Route(AlertLevel.NOTIFY, AlertReason.CONSOLE), Routing.route(console, discreet, 0), "the console sees it like any report, without an alarm")
        assertEquals(AlertLevel.SILENT, level(guard, discreet), "not even security, whose zone it is")
        assertEquals(AlertLevel.SILENT, level(medic, discreet), "nor a medic")
        assertEquals(AlertLevel.VIBRATE, level(guard, discreet, 2 * WIDEN_AFTER_MS), "until nobody has taken it for a while")
    }

    @Test
    fun aSafeguardingLeadIsToldAsALead() {
        val discreet = incident(severity = Severity.MEDIUM, category = IncidentCategory.HARASSMENT).copy(description = Discreet.describe("x"))
        val lead = StaffProfile(console = false, team = "Safeguarding 1", lead = true)
        assertEquals(Route(AlertLevel.NOTIFY, AlertReason.LEAD), Routing.route(lead, discreet, 0))
    }

    @Test
    fun onceSafeguardingHasItNothingRingsAnymore() {
        val taken = incident(severity = Severity.MEDIUM, category = IncidentCategory.HARASSMENT, status = AckStatus.ACKNOWLEDGED, team = "Safeguarding 1")
            .copy(description = Discreet.describe("x"))
        assertEquals(AlertLevel.SILENT, level(StaffProfile(console = false, team = "Safeguarding 2"), taken))
    }
}
