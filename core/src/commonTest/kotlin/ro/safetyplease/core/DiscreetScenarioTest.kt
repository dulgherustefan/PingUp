package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.incidents.AlertLevel
import ro.safetyplease.core.incidents.AlertReason
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Severity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** "Mă simt în nesiguranță" (roadmap 3.17): from the reporter, over the mesh, to the safeguarding team. */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscreetScenarioTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val discreet = ReportDraft(
        IncidentCategory.HARASSMENT, Severity.MEDIUM, "camping", 43.9505, 28.6350, Discreet.describe("Mă simt în nesiguranță"), null,
    )

    @Test
    fun aDiscreetReportAdvertisesNothingWhileItWaitsAndReachesSafeguardingMarked() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 500)
        val b = TestPhone("B", this, net, clock, tmp.root, event, 501)
        val d = TestPhone("D", this, net, clock, tmp.root, event, 502)
        d.staffSecret = event.secret
        d.team = "Safeguarding 1"
        d.engine.setRole(staff = true, anchor = false)
        a.ignore(d)
        d.ignore(a)
        net.start()
        advanceTimeBy(30_000)

        d.radio.powerOff()
        advanceTimeBy(2_000)
        a.incidents.report(discreet)
        advanceTimeBy(10 * 60_000L)
        assertTrue(IncidentManager.isPending(a.myReports.single(), clock.wallMs()), "still waiting for staff")
        assertTrue(a.radio.advertised.isNotEmpty())
        assertTrue(a.radio.advertised.none { it and NodeFlags.PENDING_INCIDENT != 0 }, "the waiting report is never in the advertising")
        assertEquals(0, b.engine.state.value.links.single { it.peerId == a.nodeId }.flags and NodeFlags.PENDING_INCIDENT)

        d.radio.powerOn()
        advanceTimeBy(30_000)
        val seen = d.staffIncidents.single()
        assertTrue(Discreet.isDiscreet(seen.description))
        assertEquals("Mă simt în nesiguranță", seen.description.removePrefix(Discreet.MARK), "the words come through as text too")
        assertEquals(Discreet.ROLE, Routing.roleOf(seen))
        assertEquals(AlertLevel.VIBRATE to AlertReason.ROLE, Routing.route(Routing.profile(d.settingsFor()), seen, clock.wallMs()).let { it.level to it.reason })
    }

    private fun TestPhone.settingsFor() = Settings(role = Role.STAFF, teamName = team)
}
