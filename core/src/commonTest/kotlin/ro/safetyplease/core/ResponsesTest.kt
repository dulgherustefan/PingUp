package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.incidents.UpdateDraft
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.IncidentCache
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.mesh.V10Node
import ro.safetyplease.core.protocol.HeldEntry
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.IncidentReportCodec
import ro.safetyplease.core.protocol.IncidentResponse
import ro.safetyplease.core.protocol.IncidentUpdateCodec
import ro.safetyplease.core.protocol.LocationFields
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.ResponseCode
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.protocol.SummaryKind
import ro.safetyplease.core.protocol.UpdateKind
import ro.safetyplease.core.util.toLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The console's quick responses to a reporter and the reporter's updates (PROTOCOL.md, sections 6.10 and 6.11), end to
 * end on the simulated radio: store-and-forward to a reporter that was away, who may sign a response, the order of
 * updates on the console, and what a 1.0 phone makes of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResponsesTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private inner class World(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val net = SimNet(scope.backgroundScope)
        val event = TestEvent(dayKey = true)
        private var seed = 900L

        fun phone(name: String) = TestPhone(name, scope, net, clock, tmp.root, event, seed++)

        fun shift(name: String, role: Int, team: String) = phone(name).also { it.activate(event.shift(role, team)) }
    }

    private fun draft() = ReportDraft(IncidentCategory.MEDICAL, Severity.URGENT, "main-stage", 43.9505, 28.6350, "A căzut", null)

    private fun TestScope.started(w: World) {
        w.net.start()
        advanceTimeBy(30_000)
    }

    private fun TestPhone.drops(reason: DropReason): Long = engine.state.value.counters.drops[reason] ?: 0

    @Test
    fun aReporterThatWasAwayGetsTheResponseWhenItComesBack() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val relay = w.phone("X")
        val console = w.shift("K", CertRole.CONTROL, "Control")
        w.net.outOfRange += setOf("R", "K")
        started(w)
        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val id = reporter.myReports.single().incidentId
        assertTrue(console.staffIncidents.single().replyKey.isNotEmpty(), "the console holds the reply key")

        reporter.radio.powerOff()
        advanceTimeBy(2_000)
        console.incidents.respond(id, ResponseCode.GO_TO_LANDMARK, landmark = 4)
        advanceTimeBy(10_000)
        assertTrue(reporter.myReports.single().responses.isEmpty())
        assertEquals(ResponseCode.GO_TO_LANDMARK, console.staffIncidents.single().responses.single().code)

        reporter.radio.powerOn()
        advanceTimeBy(70_000)
        val response = reporter.myReports.single().responses.single()
        assertEquals(listOf(ResponseCode.GO_TO_LANDMARK, 4), listOf(response.code, response.landmark))
        assertEquals(1, reporter.reportResponses.size, "the screen hears of it once")
        assertEquals(0, relay.drops(DropReason.MALFORMED), "the relay can't read it, only carry it")

        console.incidents.respond(id, ResponseCode.COMING)
        advanceTimeBy(10_000)
        assertEquals(listOf(ResponseCode.GO_TO_LANDMARK, ResponseCode.COMING), reporter.myReports.single().responses.map { it.code })
        assertTrue(reporter.myReports.single().responses.zipWithNext().all { (a, b) -> b.at > a.at }, "one second apart at least")
    }

    @Test
    fun onlyAControlShiftAnswersAReporter() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val relay = w.phone("X")
        val medic = w.shift("M", CertRole.STAFF, "Medical 1")
        w.net.outOfRange += setOf("R", "M")
        started(w)
        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val id = reporter.myReports.single().incidentId

        medic.incidents.respond(id, ResponseCode.COMING)
        advanceTimeBy(10_000)
        assertTrue(reporter.myReports.single().responses.isEmpty())
        assertTrue(relay.drops(DropReason.UNTRUSTED) > 0)
        assertEquals(0, relay.drops(DropReason.MALFORMED), "refused, not counted against the link")
    }

    @Test
    fun theConsoleShowsUpdatesInTheReportersOrderWhateverOrderTheyCameIn() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val console = w.shift("K", CertRole.CONTROL, "Control")
        started(w)
        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val id = reporter.myReports.single().incidentId

        // the first update is held back on the way, so the second arrives first
        var held: ByteArray? = null
        reporter.radio.tamper = { frame ->
            val first = frame[1].toInt() == PacketType.INCIDENT_UPDATE && frame[PacketCodec.HEADER_SIZE + 16].toInt() == 1
            if (first) null.also { held = frame } else frame
        }
        assertTrue(reporter.incidents.update(id, UpdateDraft(UpdateKind.STILL_ACTIVE)))
        assertFalse(reporter.incidents.update(id, UpdateDraft(UpdateKind.STILL_ACTIVE)), "one a minute")
        advanceTimeBy(IncidentManager.UPDATE_INTERVAL_MS)
        val moved = UpdateDraft(UpdateKind.MOVED, "bar", 43.9510, 28.6360, LocationFields(9, 4))
        assertTrue(reporter.incidents.update(id, moved))
        advanceTimeBy(5_000)
        assertEquals(listOf(2), console.staffIncidents.single().updates.map { it.seq })

        reporter.radio.tamper = null
        val link = reporter.radio.links.entries.first { it.value.radio === console.radio }.key
        reporter.radio.inject(link, held!!)
        advanceTimeBy(5_000)
        val updates = console.staffIncidents.single().updates
        assertEquals(listOf(1, 2), updates.map { it.seq })
        assertEquals(listOf(UpdateKind.STILL_ACTIVE, UpdateKind.MOVED), updates.map { it.kind })
        assertEquals(listOf("bar", 9, 4), listOf(updates[1].zone, updates[1].accuracyM, updates[1].landmark))
        assertEquals(listOf(1, 2), reporter.myReports.single().updates.map { it.seq }, "the reporter keeps what it said")
    }

    @Test
    fun aConsoleThatWasAwayGetsTheLatestUpdateWithTheReport() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        w.phone("X")
        val console = w.shift("K", CertRole.CONTROL, "Control")
        w.net.outOfRange += setOf("R", "K")
        console.radio.powerOff()
        started(w)
        reporter.incidents.report(draft())
        advanceTimeBy(5_000)
        val id = reporter.myReports.single().incidentId
        reporter.incidents.update(id, UpdateDraft(UpdateKind.STILL_ACTIVE))
        advanceTimeBy(IncidentManager.UPDATE_INTERVAL_MS)
        reporter.incidents.update(id, UpdateDraft(UpdateKind.RESOLVED_ITSELF))
        advanceTimeBy(5_000)

        console.radio.powerOn()
        advanceTimeBy(70_000)
        val incident = console.staffIncidents.single()
        assertEquals(listOf(UpdateKind.RESOLVED_ITSELF), incident.updates.map { it.kind }, "relays keep the latest")
        assertEquals(2, incident.updates.single().seq)
    }

    @Test
    fun anUpdateWithoutTheAuthorsTokenIsIgnored() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val other = w.phone("O")
        val console = w.shift("K", CertRole.CONTROL, "Control")
        started(w)
        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val mine = reporter.myReports.single()
        // another phone that learned the incidentId writes an update with a token of its own
        other.incidentStore.update { it.copy(mine = listOf(mine.copy(cancelToken = "00".repeat(16), updates = emptyList()))) }
        assertTrue(other.incidents.update(mine.incidentId, UpdateDraft(UpdateKind.RESOLVED_ITSELF)))
        advanceTimeBy(10_000)
        assertTrue(console.staffIncidents.single().updates.isEmpty())
    }

    @Test
    fun aClosedOrWithdrawnReportTakesNoUpdates() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        started(w)
        reporter.incidents.report(draft())
        advanceTimeBy(5_000)
        val id = reporter.myReports.single().incidentId
        reporter.incidents.cancel(id)
        advanceTimeBy(1_000)
        assertNull(reporter.incidents.updateWaitMs(id))
        assertFalse(reporter.incidents.update(id, UpdateDraft(UpdateKind.STILL_ACTIVE)))
    }

    @Test
    fun theCacheKeepsTheLatestOfEachAndTakesThemBackAfterARestart() {
        val cache = IncidentCache()
        val id = ByteArray(INCIDENT_ID_SIZE) { 5 }
        fun response(at: Long) = IncidentResponse(id, at, ByteArray(IncidentReportCodec.SEAL_OVERHEAD + 2), null, ByteArray(64))
        fun packet(type: Int, payload: ByteArray) = Packet(type, 6, at++, 0L, 1L, null, false, payload)
        val update = { seq: Int -> IncidentUpdateCodec.encode(id, seq, ByteArray(IncidentReportCodec.SEAL_OVERHEAD + 22)) }

        assertTrue(cache.offerResponse(response(100), packet(PacketType.INCIDENT_RESPONSE, response(100).encode()), 0))
        assertFalse(cache.offerResponse(response(99), packet(PacketType.INCIDENT_RESPONSE, response(99).encode()), 1), "an older response")
        assertTrue(cache.offerUpdate(id, 2, packet(PacketType.INCIDENT_UPDATE, update(2)), 2))
        assertFalse(cache.offerUpdate(id, 1, packet(PacketType.INCIDENT_UPDATE, update(1)), 3), "an older update")
        val id8 = id.toLong()
        assertEquals(listOf(HeldEntry(id8, 100)), cache.held(SummaryKind.RESPONSES, 4))
        assertEquals(listOf(id8), cache.missing(SummaryKind.UPDATES, listOf(HeldEntry(id8, 3), HeldEntry(7L, 0)), 4))
        assertTrue(cache.missing(SummaryKind.RESPONSES, listOf(HeldEntry(id8, 100)), 4).isEmpty())

        val restored = IncidentCache().apply { restore(cache.snapshot(5, 1_000_000), 0, 1_000_000, 60 * 60_000L) }
        assertEquals(listOf(HeldEntry(id8, 2)), restored.held(SummaryKind.UPDATES, 0))
        assertEquals(PacketType.INCIDENT_RESPONSE, restored.kept(SummaryKind.RESPONSES, id8)?.type)

        val later = IncidentCache().apply { restore(cache.snapshot(5, 1_000_000), 0, 1_000_000 + 2 * 60 * 60_000L, 60 * 60_000L) }
        assertNull(later.kept(SummaryKind.RESPONSES, id8), "too old to serve")
        assertFalse(later.offerResponse(response(100), packet(PacketType.INCIDENT_RESPONSE, response(100).encode()), 0), "but still known, so it isn't taken again")
    }

    private var at = 1L

    @Test
    fun aOneZeroPhoneNeverSeesAResponseOrAnUpdate() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val console = w.shift("K", CertRole.CONTROL, "Control")
        val old = V10Node(backgroundScope, w.net, "O", 0x0070_0000_0000_0007L, w.clock, { w.event.staffCrypto.verifyAck(it) })
        started(w)
        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val id = reporter.myReports.single().incidentId
        console.incidents.respond(id, ResponseCode.GO_TO_MEDICAL)
        reporter.incidents.update(id, UpdateDraft(UpdateKind.STILL_ACTIVE))
        advanceTimeBy(70_000)

        assertEquals(ResponseCode.GO_TO_MEDICAL, reporter.myReports.single().responses.single().code)
        assertEquals(1, console.staffIncidents.single().updates.size)
        assertTrue(old.linkCount > 0)
        assertTrue(old.received.none { it.type == PacketType.INCIDENT_RESPONSE || it.type == PacketType.INCIDENT_UPDATE })
        assertEquals(0, old.malformed)
    }
}
