package ro.safetyplease.core.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.TestEvent
import ro.safetyplease.core.TestPhone
import ro.safetyplease.core.delete
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.utf8
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The console on the simulated radio: a reporter, the console and a medical team, all in range. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleFlowTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private class Site(val reporter: TestPhone, val console: TestPhone, val medic: TestPhone)

    private fun TestScope.site(): Site {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val reporter = TestPhone("A", this, net, clock, tmp.root, event, 1)
        val console = TestPhone("K", this, net, clock, tmp.root, event, 2)
        val medic = TestPhone("M", this, net, clock, tmp.root, event, 3)
        for ((phone, team) in listOf(console to "Control", medic to "Medical 2")) {
            phone.staffSecret = event.secret
            phone.team = team
            phone.engine.setRole(staff = true, anchor = false)
        }
        net.start()
        advanceTimeBy(30_000)
        return Site(reporter, console, medic)
    }

    private fun medical(n: Int = 0) =
        ReportDraft(IncidentCategory.MEDICAL, Severity.URGENT, "main-stage", 43.9505, 28.6350, "A leșinat $n", null)

    @Test
    fun assignmentReachesTheTeamAndTheReporterAsTheTeamsAck() = runTest {
        val s = site()
        assertTrue(s.reporter.incidents.report(medical()))
        advanceTimeBy(15_000)
        val id = s.console.staffIncidents.single().incidentId

        s.console.incidents.assign(id, "Medical 2")
        advanceTimeBy(15_000)

        val atMedic = s.medic.staffIncidents.single()
        assertEquals(AckStatus.ACKNOWLEDGED, atMedic.status)
        assertTrue(isAssignedTo(atMedic.teamName, s.medic.team))
        assertTrue(atMedic.assigned, "flagged as the console's assignment")
        assertEquals(AckStatus.ACKNOWLEDGED, s.reporter.myReports.single().status)
        assertEquals("Medical 2", s.reporter.myReports.single().teamName)

        val (signed, own, hops) = s.console.acks.last()
        assertEquals(AckStatus.ACKNOWLEDGED, signed.status)
        assertEquals("Medical 2", signed.teamName)
        assertTrue(signed.assigned)
        assertTrue(own && hops == 0)
        val heard = s.medic.acks.single { it.first.status == AckStatus.ACKNOWLEDGED }
        assertTrue(!heard.second && heard.third >= 1, "signed elsewhere, it came over the radio")
        assertTrue(heard.first.signature.contentEquals(signed.signature))
    }

    @Test
    fun aTeamsOwnTakeReplacesTheAssignmentEverywhere() = runTest {
        val s = site()
        s.reporter.incidents.report(medical())
        advanceTimeBy(15_000)
        val id = s.console.staffIncidents.single().incidentId
        s.console.incidents.assign(id, "Medical")
        advanceTimeBy(15_000)
        assertTrue(s.medic.staffIncidents.single().assigned)

        // signed later than the assignment, and still the one that stands
        s.medic.incidents.acknowledge(id)
        advanceTimeBy(15_000)

        for (phone in listOf(s.medic, s.console)) {
            val incident = phone.staffIncidents.single()
            assertEquals("Medical 2", incident.teamName, phone.name)
            assertFalse(incident.assigned, phone.name)
        }
        assertEquals("Medical 2", s.reporter.myReports.single().teamName)
        val (take, own, _) = s.console.acks.last()
        assertTrue(!own && !take.assigned && take.teamName == "Medical 2")
    }

    @Test
    fun assignmentStaysOnTheConsoleOnceATeamTookTheIncident() = runTest {
        val s = site()
        s.reporter.incidents.report(medical())
        advanceTimeBy(15_000)
        val id = s.console.staffIncidents.single().incidentId
        s.medic.incidents.acknowledge(id)
        advanceTimeBy(15_000)
        val before = s.console.acks.size

        s.console.incidents.assign(id, "Securitate 1")
        advanceTimeBy(15_000)

        assertEquals(before, s.console.acks.size, "nothing signed went out")
        assertEquals("Medical 2", s.medic.staffIncidents.single().teamName)
        assertEquals("Medical 2", s.reporter.myReports.single().teamName)
    }

    @Test
    fun aTeamNameTooLongForTheAckIsCut() = runTest {
        val s = site()
        s.reporter.incidents.report(medical())
        advanceTimeBy(15_000)
        val id = s.console.staffIncidents.single().incidentId

        s.console.incidents.assign(id, "Echipa medicală de intervenție rapidă")
        advanceTimeBy(15_000)

        val team = s.medic.staffIncidents.single().teamName
        assertTrue(team.startsWith("Echipa medicală"))
        assertTrue(team.utf8().size <= Limits.TEAM_BYTES)
    }

    @Test
    fun theConsoleKeepsMoreIncidentsThanAPhoneInTheField() = runTest {
        val s = site()
        s.console.staffCap = 10
        s.medic.staffCap = 2
        repeat(4) { s.reporter.incidents.report(medical(it), bypassRateLimit = true) }
        advanceTimeBy(30_000)
        assertEquals(4, s.console.staffIncidents.size)
        assertEquals(2, s.medic.staffIncidents.size)
    }
}
