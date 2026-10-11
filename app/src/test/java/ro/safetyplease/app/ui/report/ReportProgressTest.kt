package ro.safetyplease.app.ui.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.app.R
import ro.safetyplease.app.text.ESCALATE_AFTER_MS
import ro.safetyplease.app.text.EscalationReminder
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.escalationAt
import ro.safetyplease.app.text.escalationsBetween
import ro.safetyplease.app.text.needsEscalation
import ro.safetyplease.app.text.nextEscalation
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone

class ReportProgressTest {
    private val t0 = 1_000_000L

    private fun report(
        id: String = "r",
        severity: Int = Severity.URGENT,
        status: Int = AckStatus.NONE,
        sent: Boolean = false,
        cancelled: Boolean = false,
        team: String = "",
        createdAt: Long = t0,
    ) = MyReport(
        incidentId = id, category = IncidentCategory.MEDICAL, severity = severity, zone = "", createdAt = createdAt,
        status = status, teamName = team, sent = sent, cancelled = cancelled,
    )

    // --- honest steps ---

    @Test
    fun receivedSpeaksOfAPhoneNotOfSomeoneWhoSawIt() {
        val received = report(status = AckStatus.RECEIVED, sent = true)
        assertEquals(2, reportStep(received))
        assertEquals(R.string.status_received, stepLabel(2, received))
        assertEquals(R.string.status_received_note, stepNote(2))
        assertEquals(R.string.status_received_full, Labels.myReportStatus(AckStatus.RECEIVED, hasTeam = false))
    }

    @Test
    fun seenOnlyOnceAPersonOnStaffTookIt() {
        val taken = report(status = AckStatus.ACKNOWLEDGED, sent = true, team = "Medical 1")
        assertEquals(3, reportStep(taken))
        assertEquals(R.string.status_acknowledged, stepLabel(3, taken))
        assertEquals(R.string.status_acknowledged, Labels.myReportStatus(AckStatus.ACKNOWLEDGED, hasTeam = true))
        assertEquals(R.string.status_acknowledged_pending, Labels.myReportStatus(AckStatus.ACKNOWLEDGED, hasTeam = false))
        assertNull("no note once a person confirmed", stepNote(3))
        // the step still to come names no team
        assertEquals(R.string.status_acknowledged_pending, stepLabel(3, report(status = AckStatus.RECEIVED, team = "Medical 1")))
    }

    @Test
    fun stepsFollowTheReportFromWaitingToResolved() {
        assertEquals(0, reportStep(report()))
        assertEquals(R.string.status_waiting_link, stepLabel(0, report()))
        assertEquals(1, reportStep(report(sent = true)))
        assertEquals(R.string.status_sending, stepLabel(1, report(sent = true)))
        assertEquals(4, reportStep(report(status = AckStatus.RESOLVED)))
        assertEquals(R.string.status_resolved, Labels.myReportStatus(AckStatus.RESOLVED, hasTeam = true))
        assertNull("nothing to tell before a staff phone has it", Labels.myReportStatus(AckStatus.NONE, hasTeam = false))
        assertNull(Labels.myReportStatus(AckStatus.CANCELLED, hasTeam = false))
    }

    // --- escalation ---

    @Test
    fun urgentReportEscalatesAfterTheDelayUntilAPersonConfirms() {
        val waiting = report()
        assertFalse(waiting.needsEscalation(t0 + ESCALATE_AFTER_MS - 1))
        assertTrue(waiting.needsEscalation(t0 + ESCALATE_AFTER_MS))
        assertTrue("an automatic RECEIVED doesn't stop it", report(status = AckStatus.RECEIVED).needsEscalation(t0 + ESCALATE_AFTER_MS))
        assertFalse(report(status = AckStatus.ACKNOWLEDGED).needsEscalation(t0 + 60 * 60_000L))
        assertFalse(report(status = AckStatus.RESOLVED).needsEscalation(t0 + 60 * 60_000L))
    }

    @Test
    fun notUrgentOrWithdrawnReportsNeverEscalate() {
        assertNull(escalationAt(report(severity = Severity.MEDIUM)))
        assertNull(escalationAt(report(cancelled = true)))
        assertNull(escalationAt(report(status = AckStatus.CANCELLED, cancelled = true)))
        assertEquals(t0 + ESCALATE_AFTER_MS, escalationAt(report()))
    }

    @Test
    fun reminderFiresOnceForEachMomentThatPasses() {
        val first = report("a")
        val later = report("b", createdAt = t0 + 60_000)
        val calm = report("c", severity = Severity.MEDIUM)
        val mine = listOf(first, later, calm)
        val at = t0 + ESCALATE_AFTER_MS
        assertEquals(at, nextEscalation(mine, t0))
        assertEquals(emptyList<MyReport>(), escalationsBetween(mine, t0, at - 1))
        assertEquals(listOf(first), escalationsBetween(mine, at - 1, at))
        assertEquals("already told", emptyList<MyReport>(), escalationsBetween(mine, at, at + 1_000))
        assertEquals(at + 60_000, nextEscalation(mine, at))
        assertNull(nextEscalation(mine, at + 60_000))
        assertEquals("moments before the app started aren't told", emptyList<MyReport>(), escalationsBetween(mine, at + 90_000, at + 150_000))
    }

    @Test
    fun momentPassedInTheForegroundIsToldWhenTheAppLeaves() {
        val waiting = report("a")
        val at = t0 + ESCALATE_AFTER_MS
        val reminder = EscalationReminder(t0)
        assertEquals(emptyList<MyReport>(), reminder.due(listOf(waiting), at, foreground = true))
        assertEquals(emptyList<MyReport>(), reminder.due(listOf(waiting), at + 30_000, foreground = true))
        assertEquals(listOf(waiting), reminder.due(listOf(waiting), at + 60_000, foreground = false))
        assertEquals("only once", emptyList<MyReport>(), reminder.due(listOf(waiting), at + 90_000, foreground = false))
    }

    @Test
    fun heldMomentIsDroppedIfStaffConfirmsMeanwhile() {
        val at = t0 + ESCALATE_AFTER_MS
        val reminder = EscalationReminder(t0)
        reminder.due(listOf(report("a")), at, foreground = true)
        val taken = report("a", status = AckStatus.ACKNOWLEDGED, team = "Medical 1")
        assertEquals(emptyList<MyReport>(), reminder.due(listOf(taken), at + 60_000, foreground = false))
    }

    @Test
    fun inTheBackgroundTheMomentIsToldRightAway() {
        val waiting = report("a")
        val reminder = EscalationReminder(t0)
        assertEquals(listOf(waiting), reminder.due(listOf(waiting), t0 + ESCALATE_AFTER_MS, foreground = false))
        val restarted = EscalationReminder(t0 + ESCALATE_AFTER_MS)
        val later = t0 + 2 * ESCALATE_AFTER_MS
        assertEquals("not again after a restart", emptyList<MyReport>(), restarted.due(listOf(waiting), later, foreground = false))
    }

    // --- where help is measured from ---

    private val camping = Zone(
        "camping", "Camping", "#000000",
        listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 2.0), GeoPoint(2.0, 2.0), GeoPoint(2.0, 0.0)), type = "camping",
    )
    private val venue = Venue("test", Bounds(0.0, 0.0, 4.0, 4.0), listOf(camping))

    @Test
    fun helpIsMeasuredFromTheBestPositionWeKnow() {
        val here = GeoPoint(3.0, 3.0)
        val reported = report().copy(lat = 0.5, lon = 0.5, zone = "camping")
        assertEquals(here, helpOrigin(here, reported, "", venue))
        assertEquals(GeoPoint(0.5, 0.5), helpOrigin(null, reported, "", venue))
        assertEquals(camping.center, helpOrigin(null, report().copy(zone = "camping"), "", venue))
        assertEquals("the zone picked by hand", camping.center, helpOrigin(null, report(), "camping", venue))
        assertNull(helpOrigin(null, report(), "", venue))
    }
}
