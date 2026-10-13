package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Routing.WIDEN_AFTER_MS
import ro.safetyplease.core.incidents.UrgentEscalation.ACK_WITHIN_MS
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UrgentEscalationTest {
    private val start = 1_000_000L
    private val console = StaffProfile(console = true, team = "Control")
    private val leadElsewhere = StaffProfile(console = false, team = "Medical 1", zone = "camping", lead = true)
    private val leadHere = StaffProfile(console = false, team = "Medical 3", zone = "main-stage", lead = true)
    private val medicElsewhere = StaffProfile(console = false, team = "Medical 2", zone = "camping")
    private val guardLead = StaffProfile(console = false, team = "Securitate 1", zone = "camping", lead = true)

    private fun incident(severity: Int = Severity.URGENT, status: Int = AckStatus.RECEIVED, statusAt: Long = start) = StaffIncident(
        incidentId = "a", category = IncidentCategory.MEDICAL, severity = severity, zone = "main-stage", reportedAt = start,
        receivedAt = start, hops = 1, status = status, statusAt = statusAt,
    )

    private fun route(me: StaffProfile, incident: StaffIncident, after: Long) = Routing.route(me, incident, start + after)

    @Test
    fun anUrgentIncidentIsOverdueAfterTwoMinutesUnlessSomeoneHasIt() {
        val urgent = incident()
        assertEquals(start + ACK_WITHIN_MS, UrgentEscalation.deadline(urgent))
        assertFalse(UrgentEscalation.overdue(urgent, start + ACK_WITHIN_MS - 1))
        assertTrue(UrgentEscalation.overdue(urgent, start + ACK_WITHIN_MS))
        assertNull(UrgentEscalation.deadline(incident(severity = Severity.MEDIUM)), "only urgencies run the clock")
        assertNull(UrgentEscalation.deadline(incident(status = AckStatus.ACKNOWLEDGED)), "a team has it")
        assertNull(UrgentEscalation.deadline(incident(status = AckStatus.CLOSED_BY_CONTROL)))
        val reopened = incident(status = AckStatus.REOPENED, statusAt = start + 600_000)
        assertEquals(start + 600_000 + ACK_WITHIN_MS, UrgentEscalation.deadline(reopened), "a reopen starts the clock again")
    }

    @Test
    fun theLeadsOfTheRoleHearItAtTwoMinutesWhereverTheyStand() {
        val urgent = incident()
        assertEquals(Route(AlertLevel.INSISTENT, AlertReason.LEAD), route(leadHere, urgent, 0), "the lead of the zone hears it at once, as before")
        assertEquals(AlertLevel.SILENT, route(leadElsewhere, urgent, 0).level)
        assertEquals(AlertLevel.SILENT, route(leadElsewhere, urgent, WIDEN_AFTER_MS).level, "no vibration first: the escalation is the one alert")
        assertEquals(Route(AlertLevel.INSISTENT, AlertReason.ESCALATED), route(leadElsewhere, urgent, ACK_WITHIN_MS))
        assertEquals(Route(AlertLevel.INSISTENT, AlertReason.ESCALATED), route(leadElsewhere, urgent, 2 * WIDEN_AFTER_MS), "and stays so")
        assertEquals(AlertLevel.SILENT, route(guardLead, urgent, ACK_WITHIN_MS).level, "only the incident's role")
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.ROLE), route(medicElsewhere, urgent, WIDEN_AFTER_MS), "the widening of the role is unchanged")
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.EVERYONE), route(guardLead, urgent, 2 * WIDEN_AFTER_MS))
        assertEquals(AlertLevel.SILENT, route(leadElsewhere, incident(status = AckStatus.ACKNOWLEDGED), ACK_WITHIN_MS).level)
        assertEquals(start + ACK_WITHIN_MS, Routing.nextChange(leadElsewhere, urgent, start + WIDEN_AFTER_MS), "the phone wakes for it")
    }

    @Test
    fun theEscalationRingsEachPhoneOnceAndAddsNothingOnTheConsole() {
        val urgent = incident()
        val lead = AlertPlanner(start)
        val consolePlanner = AlertPlanner(start)
        val leadPosts = mutableListOf<Pair<Long, AlertPost>>()
        val consolePosts = mutableListOf<Pair<Long, AlertPost>>()
        var t = start
        while (t <= start + 3 * 60_000) {
            lead.plan(leadElsewhere, listOf(urgent), emptySet(), t).post.forEach { leadPosts += t to it }
            consolePlanner.plan(console, listOf(urgent), emptySet(), t).post.forEach { consolePosts += t to it }
            t += 5_000
        }
        val first = leadPosts.first()
        assertEquals(start + ACK_WITHIN_MS, first.first, "the lead hears nothing before two minutes")
        assertEquals(AlertReason.ESCALATED, first.second.route.reason)
        assertTrue(first.second.fullScreen)
        assertEquals(1, leadPosts.count { it.second.fullScreen }, "one full screen")
        assertTrue(leadPosts.drop(1).all { it.second.route.reason == AlertReason.ESCALATED && !it.second.fullScreen }, "then only the insistent repeat")
        assertTrue(leadPosts.zipWithNext().all { (a, b) -> b.first - a.first >= AlertPlanner.REPEAT_MS })

        assertEquals(1, consolePosts.count { it.second.fullScreen })
        assertTrue(consolePosts.all { it.second.route.reason == AlertReason.CONSOLE }, "the console keeps its own repeat, nothing new at two minutes")
        assertTrue(consolePosts.zipWithNext().all { (a, b) -> b.first - a.first >= AlertPlanner.REPEAT_MS })
    }

    @Test
    fun theConsoleTimerCountsFromOpeningOrFromTheReopen() {
        assertEquals(1_000 + ACK_WITHIN_MS, UrgentEscalation.consoleDeadline(urgent = true, open = true, openedAt = 1_000, reopenedAt = null))
        assertEquals(9_000 + ACK_WITHIN_MS, UrgentEscalation.consoleDeadline(urgent = true, open = true, openedAt = 1_000, reopenedAt = 9_000))
        assertNull(UrgentEscalation.consoleDeadline(urgent = true, open = false, openedAt = 1_000, reopenedAt = null))
        assertNull(UrgentEscalation.consoleDeadline(urgent = false, open = true, openedAt = 1_000, reopenedAt = null))
    }
}
