package ro.safetyplease.app.ui.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.app.ui.common.helpOrigin
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.incidents.ESCALATE_AFTER_MS
import ro.safetyplease.core.incidents.EscalationReminder
import ro.safetyplease.core.incidents.escalationAt
import ro.safetyplease.core.incidents.escalationsBetween
import ro.safetyplease.core.incidents.needsEscalation
import ro.safetyplease.core.incidents.nextEscalation
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.status_acknowledged
import ro.safetyplease.ui.resources.status_acknowledged_pending
import ro.safetyplease.ui.resources.status_closed_by_control
import ro.safetyplease.ui.resources.status_handed_over
import ro.safetyplease.ui.resources.status_on_scene
import ro.safetyplease.ui.resources.status_received
import ro.safetyplease.ui.resources.status_received_full
import ro.safetyplease.ui.resources.status_received_note
import ro.safetyplease.ui.resources.status_reopened
import ro.safetyplease.ui.resources.status_resolved
import ro.safetyplease.ui.resources.status_sending
import ro.safetyplease.ui.resources.status_unfounded
import ro.safetyplease.ui.resources.status_waiting_link
import ro.safetyplease.ui.text.Labels

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
        assertEquals(Res.string.status_received, stepLabel(2, received))
        assertEquals(Res.string.status_received_note, stepNote(2))
        assertEquals(Res.string.status_received_full, Labels.myReportStatus(AckStatus.RECEIVED, hasTeam = false))
    }

    @Test
    fun seenOnlyOnceAPersonOnStaffTookIt() {
        val taken = report(status = AckStatus.ACKNOWLEDGED, sent = true, team = "Medical 1")
        assertEquals(3, reportStep(taken))
        assertEquals(Res.string.status_acknowledged, stepLabel(3, taken))
        assertEquals(Res.string.status_acknowledged, Labels.myReportStatus(AckStatus.ACKNOWLEDGED, hasTeam = true))
        assertEquals(Res.string.status_acknowledged_pending, Labels.myReportStatus(AckStatus.ACKNOWLEDGED, hasTeam = false))
        assertNull("no note once a person confirmed", stepNote(3))
        // the step still to come names no team
        assertEquals(Res.string.status_acknowledged_pending, stepLabel(3, report(status = AckStatus.RECEIVED, team = "Medical 1")))
    }

    @Test
    fun stepsFollowTheReportFromWaitingToResolved() {
        assertEquals(0, reportStep(report()))
        assertEquals(Res.string.status_waiting_link, stepLabel(0, report()))
        assertEquals(1, reportStep(report(sent = true)))
        assertEquals(Res.string.status_sending, stepLabel(1, report(sent = true)))
        assertEquals(4, reportStep(report(status = AckStatus.RESOLVED)))
        assertEquals(Res.string.status_resolved, Labels.myReportStatus(AckStatus.RESOLVED, hasTeam = true))
        assertNull("nothing to tell before a staff phone has it", Labels.myReportStatus(AckStatus.NONE, hasTeam = false))
        assertNull(Labels.myReportStatus(AckStatus.CANCELLED, hasTeam = false))
    }

    @Test
    fun fieldStatesAndClosingsNameTheirOwnStep() {
        val onScene = report(status = AckStatus.ON_SCENE, sent = true, team = "Medical 1")
        assertEquals(3, reportStep(onScene))
        assertEquals(Res.string.status_on_scene, stepLabel(3, onScene))
        assertEquals(Res.string.status_handed_over, stepLabel(3, report(status = AckStatus.HANDED_OVER, team = "Medical 1")))
        val reopened = report(status = AckStatus.REOPENED, team = "Control")
        assertEquals("back with staff", 3, reportStep(reopened))
        assertEquals(Res.string.status_reopened, stepLabel(3, reopened))
        val closed = report(status = AckStatus.CLOSED_BY_CONTROL, team = "Control")
        assertEquals(4, reportStep(closed))
        assertEquals(Res.string.status_closed_by_control, stepLabel(4, closed))
        assertEquals(Res.string.status_acknowledged, stepLabel(3, closed))
        assertEquals(Res.string.status_unfounded, stepLabel(4, report(status = AckStatus.UNFOUNDED)))
        assertEquals(Res.string.status_resolved, stepLabel(4, report(status = AckStatus.RESOLVED)))
        val words = mapOf(
            AckStatus.ON_SCENE to Res.string.status_on_scene, AckStatus.HANDED_OVER to Res.string.status_handed_over,
            AckStatus.CLOSED_BY_CONTROL to Res.string.status_closed_by_control, AckStatus.UNFOUNDED to Res.string.status_unfounded,
            AckStatus.REOPENED to Res.string.status_reopened,
        )
        for ((status, word) in words) assertEquals(word, Labels.myReportStatus(status, hasTeam = true))
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
        assertFalse("control has seen a reopened one", report(status = AckStatus.REOPENED).needsEscalation(t0 + 60 * 60_000L))
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
        assertEquals("no report: the zone picked by hand", camping.center, helpOrigin(null, null, "camping", venue))
        assertEquals(here, helpOrigin(here, null, "camping", venue))
    }

    @Test
    fun anOpenMedicalOrUrgentReportStaysPut() {
        assertTrue(staysPut(report(), escalated = false))
        assertTrue(staysPut(report().copy(severity = Severity.MEDIUM), escalated = false))
        assertFalse("then the reporter goes to a steward", staysPut(report(), escalated = true))
        assertFalse(staysPut(report(cancelled = true), escalated = false))
        assertFalse(staysPut(report(status = AckStatus.RESOLVED), escalated = false))
        assertFalse(staysPut(report(severity = Severity.MEDIUM).copy(category = IncidentCategory.CROWD), escalated = false))
    }
}
