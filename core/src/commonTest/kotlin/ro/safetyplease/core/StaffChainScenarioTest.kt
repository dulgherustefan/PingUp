package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.control.StaffDensity
import ro.safetyplease.core.incidents.PriorityRules
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.protocol.StaffFields
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PL6 over the mesh: crowd levels that never become incidents, and the priority table on the phone that opens a report. */
@OptIn(ExperimentalCoroutinesApi::class)
class StaffChainScenarioTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private fun density(level: Int) = ReportDraft(
        IncidentCategory.CROWD, StaffDensity.severity(level), "main-stage", 43.95, 28.63, "", null,
        staff = StaffFields(), extras = StaffDensity.extras(level),
    )

    @Test
    fun aRoutineLevelIsOnePacketThatNobodyAcknowledgesAndALevelFiveIsAnIncident() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val steward = TestPhone("S", this, net, clock, tmp.root, event, 600)
        val console = TestPhone("C", this, net, clock, tmp.root, event, 601)
        for (phone in listOf(steward, console)) {
            phone.staffSecret = event.secret
            phone.engine.setRole(staff = true, anchor = false)
        }
        steward.team = "Steward 1"
        net.start()
        advanceTimeBy(30_000)

        assertTrue(steward.incidents.report(density(2)))
        advanceTimeBy(30_000)
        assertTrue(steward.myReports.isEmpty(), "nothing to follow or cancel on the sender")
        assertTrue(console.staffIncidents.isEmpty(), "no incident on the console")
        val heard = console.densityReports.single()
        assertEquals(2, heard.density)
        assertEquals("main-stage", heard.zone)
        assertEquals("Steward 1", heard.nickname, "in the team's name")
        assertEquals(1, steward.densityReports.size, "the sender keeps its own level too")
        assertTrue(console.acks.isEmpty() && steward.acks.isEmpty(), "no ACK on the network for it")

        assertTrue(steward.incidents.report(density(5)))
        advanceTimeBy(30_000)
        val crowd = console.staffIncidents.single()
        assertEquals(Severity.URGENT, crowd.severity)
        assertEquals(5, crowd.density)
        assertEquals(IncidentCategory.CROWD, crowd.category)
        assertTrue(console.acks.any { it.first.status == AckStatus.RECEIVED }, "an incident like any other")
        assertEquals(1, console.densityReports.size)
    }

    @Test
    fun thePhoneThatOpensAReportRaisesItByTheTable() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val reporter = TestPhone("R", this, net, clock, tmp.root, event, 700)
        val medic = TestPhone("M", this, net, clock, tmp.root, event, 701)
        medic.staffSecret = event.secret
        medic.engine.setRole(staff = true, anchor = false)
        medic.priorityRules = PriorityRules.FALLBACK
        net.start()
        advanceTimeBy(30_000)

        reporter.incidents.report(
            ReportDraft(IncidentCategory.MEDICAL, Severity.MEDIUM, "camping", null, null, "", null, extras = ReportExtras(unconscious = true)),
        )
        reporter.incidents.report(
            ReportDraft(IncidentCategory.CROWD, Severity.MEDIUM, "camping", null, null, "", null, extras = ReportExtras(cannotMove = true)),
        )
        advanceTimeBy(30_000)
        val unconscious = medic.staffIncidents.single { it.category == IncidentCategory.MEDICAL }
        assertTrue(unconscious.unconscious)
        assertEquals(Severity.URGENT, unconscious.severity, "the reporter sent it as medium")
        assertEquals("unconscious", unconscious.topRule)
        assertEquals(1, medic.alerts.count { it.incidentId == unconscious.incidentId })
        val stuck = medic.staffIncidents.single { it.category == IncidentCategory.CROWD }
        assertTrue(stuck.cannotMove)
        assertEquals(Severity.MEDIUM, stuck.severity)
        assertEquals("", stuck.topRule)
    }
}
