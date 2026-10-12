package ro.safetyplease.core.metrics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.TestEvent
import ro.safetyplease.core.TestPhone
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import java.io.File

/** The pilot's numbers as phones on the simulated radio produce them. */
@OptIn(ExperimentalCoroutinesApi::class)
class PilotMetricsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class World(val net: SimNet, val clock: TestClock, val event: TestEvent)

    private fun TestScope.world() = World(SimNet(backgroundScope), TestClock(testScheduler), TestEvent())

    private fun TestScope.phone(w: World, name: String, seed: Long) = TestPhone(name, this, w.net, w.clock, tmp.root, w.event, seed)

    private fun staff(w: World, phone: TestPhone) {
        phone.staffSecret = w.event.secret
        phone.engine.setRole(staff = true, anchor = false)
    }

    private val medical = ReportDraft(IncidentCategory.MEDICAL, Severity.URGENT, "main-stage", 43.9505, 28.6350, "A leșinat", null)

    @Test
    fun reporterTimesTheRoundTripOnItsOwnClockWithTheHopsOfTheFirstAck() = runTest {
        val w = world()
        // A - B - C - D in a line: three hops each way
        val a = phone(w, "A", 1)
        val b = phone(w, "B", 2)
        val c = phone(w, "C", 3)
        val d = phone(w, "D", 4)
        staff(w, d)
        a.ignore(c, d)
        b.ignore(d)
        c.ignore(a)
        d.ignore(a, b)
        w.net.start()
        advanceTimeBy(30_000)

        a.incidents.report(medical)
        advanceTimeBy(15_000)
        val received = a.myReports.single()
        assertEquals(AckStatus.RECEIVED, received.status)
        assertTrue(received.receivedAt > received.createdAt)
        assertEquals(3, received.firstAckHops)
        assertEquals("nobody has it yet", 0L, received.acknowledgedAt)

        advanceTimeBy(60_000)
        d.incidents.acknowledge(d.staffIncidents.single().incidentId)
        advanceTimeBy(15_000)
        val acknowledged = a.myReports.single()
        assertTrue(acknowledged.acknowledgedAt >= received.receivedAt + 60_000)
        assertEquals("the first ACK's hops stay", 3, acknowledged.firstAckHops)
        assertEquals(received.receivedAt, acknowledged.receivedAt)

        d.incidents.resolve(acknowledged.incidentId)
        advanceTimeBy(15_000)
        assertEquals("later ACKs time nothing", acknowledged.acknowledgedAt, a.myReports.single().acknowledgedAt)

        val reportHops = d.engine.state.value.counters.reportHops
        assertEquals(mapOf(3 to 1L), reportHops)
        assertTrue(a.engine.state.value.counters.ackHops.getValue(3) >= 1)
        assertTrue(a.engine.state.value.counters.linksOpened >= 1)
    }

    @Test
    fun aStaffPhonesOwnReportIsTimedOnlyByAcksFromOthers() = runTest {
        val w = world()
        val s = phone(w, "S", 1)
        val m = phone(w, "M", 2)
        staff(w, s)
        staff(w, m)
        runCurrent()

        s.incidents.report(medical)
        advanceTimeBy(15_000)
        val mine = s.myReports.single()
        assertEquals("its own RECEIVED, signed here", AckStatus.RECEIVED, mine.status)
        assertEquals("times nothing", 0L, mine.receivedAt)

        w.net.start()
        advanceTimeBy(30_000)
        m.incidents.acknowledge(mine.incidentId)
        advanceTimeBy(15_000)
        val taken = s.myReports.single()
        assertTrue(taken.receivedAt > mine.createdAt + 15_000)
        assertTrue(taken.acknowledgedAt >= taken.receivedAt)
        assertEquals(1, taken.firstAckHops)
    }

    @Test
    fun aReportPublishedAgainAfterARestartCountsIt() = runTest {
        val w = world()
        val a = phone(w, "A", 1)
        runCurrent()
        a.incidents.report(medical)
        advanceTimeBy(2_000)
        assertEquals(0, a.myReports.single().republished)

        a.incidentStore.flush()
        a.radio.powerOff()
        File(tmp.root, "A-incidents.json").copyTo(File(tmp.root, "A2-incidents.json"))
        val again = phone(w, "A2", 2)
        runCurrent()
        assertEquals(1, again.myReports.single().republished)
        assertEquals(1, again.engine.state.value.cachedReports)
    }

    @Test
    fun linksThatGoDownAreCounted() = runTest {
        val w = world()
        val a = phone(w, "A", 1)
        val b = phone(w, "B", 2)
        w.net.start()
        advanceTimeBy(30_000)
        assertTrue(a.engine.state.value.counters.linksOpened >= 1)
        assertEquals(0L, a.engine.state.value.counters.linksClosed)

        b.radio.powerOff()
        advanceTimeBy(5_000)
        assertTrue(a.engine.state.value.counters.linksClosed >= 1)
    }
}
