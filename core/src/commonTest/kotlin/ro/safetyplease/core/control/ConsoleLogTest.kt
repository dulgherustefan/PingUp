package ro.safetyplease.core.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.delete
import ro.safetyplease.core.mesh.LinkInfo
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.SeenPeer
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleLogTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    /** The bundled venue: about 497 m by 356 m. */
    private val bounds = Bounds(43.948, 28.632, 43.9512, 28.6382)
    private val grid = SiteGrid.of(bounds)
    private val id = "0a".repeat(16)
    private val t0 = 1_760_000_000_000L

    private fun incident(status: Int = AckStatus.NONE, team: String = "", lat: Double? = 43.9505, lon: Double? = 28.6350) = StaffIncident(
        incidentId = id, category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = "main-stage", lat = lat, lon = lon,
        description = "A leșinat", reportedAt = t0, receivedAt = t0 + 20_000, hops = 3, status = status, teamName = team,
    )

    private fun ack(status: Int, team: String, atSec: Long, own: Boolean = false, epoch: Int = 0) =
        LogEntry(t0 + atSec * 1000, LogEvent.ACK, id, status = status, epoch = epoch, team = team, signedAt = t0 + atSec * 1000, own = own)

    private fun decision(event: LogEvent, team: String, atSec: Long, reason: String = "") =
        LogEntry(t0 + atSec * 1000, event, id, team = team, reason = reason)

    private fun case(vararg entries: LogEntry, stored: StaffIncident? = null): Case =
        ConsoleView.case(listOf(reportEntry(incident(), grid)) + entries, stored, grid)!!

    // --- the site grid ---

    @Test
    fun gridHasSquaresOfAboutFiftyMetersLabelledFromTheNorthWest() {
        assertEquals(10, grid.columns)
        assertEquals(7, grid.rows)
        assertEquals("A1", grid.cellAt(bounds.maxLat, bounds.minLon))
        assertEquals("J7", grid.cellAt(bounds.minLat, bounds.maxLon), "the east and south edges belong to the last square")
        assertEquals("E2", grid.cellAt(43.9505, 28.6350))
        assertNull(grid.cellAt(43.96, 28.635))
        assertEquals(bounds.minLon, grid.columnLon(0), 1e-9)
        assertEquals(bounds.maxLon, grid.columnLon(grid.columns), 1e-9)
        assertEquals(bounds.minLat, grid.rowLat(grid.rows), 1e-9)
    }

    @Test
    fun gridKeepsOneLetterPerColumnAndAtLeastOneSquare() {
        assertEquals(26, SiteGrid.of(Bounds(44.0, 26.0, 44.001, 26.1)).columns)
        val tiny = SiteGrid.of(Bounds(44.0, 26.0, 44.0001, 26.0001))
        assertEquals(1, tiny.columns)
        assertEquals(1, tiny.rows)
        assertEquals("Z1", SiteGrid.of(Bounds(44.0, 26.0, 44.0001, 26.1)).cellAt(44.0, 26.1))
    }

    @Test
    fun reportEntryTakesTheGridSquareOnlyFromCoordinates() {
        assertEquals("E2", reportEntry(incident(), grid).grid)
        assertEquals("", reportEntry(incident(lat = null, lon = null), grid).grid)
        assertEquals(t0 + 20_000, reportEntry(incident(), grid).at)
    }

    // --- the console's view of an incident ---

    @Test
    fun networkAcksMoveTheCaseAndTimeEachStep() {
        val c = case(
            ack(AckStatus.RECEIVED, "Control", 30, own = true),
            ack(AckStatus.ACKNOWLEDGED, "Medical 1", 90),
            ack(AckStatus.RESOLVED, "Medical 1", 600),
        )
        assertEquals(CaseState.CLOSED, c.state)
        assertEquals("Medical 1", c.team)
        assertFalse(c.localOnly)
        assertEquals(30L, c.secondsTo(c.receivedAt))
        assertEquals(90L, c.secondsTo(c.acknowledgedAt))
        assertEquals(600L, c.secondsTo(c.closedAt))
    }

    @Test
    fun assignmentFollowedByItsAckIsWhatTheFieldSees() {
        val c = case(
            ack(AckStatus.RECEIVED, "Control", 30, own = true),
            decision(LogEvent.ASSIGN, "Medical 2", 60),
            ack(AckStatus.ACKNOWLEDGED, "Medical 2", 60, own = true),
        )
        assertEquals(CaseState.ASSIGNED, c.state)
        assertEquals("Medical 2", c.team)
        assertFalse(c.localOnly)
        assertEquals(60L, c.secondsTo(c.acknowledgedAt))
    }

    @Test
    fun reassigningATakenIncidentStaysOnTheConsole() {
        val c = case(
            ack(AckStatus.ACKNOWLEDGED, "Medical 1", 60),
            decision(LogEvent.ASSIGN, "Medical 2", 120),
        )
        assertEquals("Medical 2", c.team)
        assertEquals("Medical 1", c.networkTeam)
        assertTrue(c.localOnly)
        // an older ACK for the same state renames the network's team, not the operator's choice
        val later = case(
            ack(AckStatus.ACKNOWLEDGED, "Medical 1", 60),
            decision(LogEvent.ASSIGN, "Medical 2", 120),
            ack(AckStatus.ACKNOWLEDGED, "Securitate 1", 50),
        )
        assertEquals("Medical 2", later.team)
        assertEquals("Securitate 1", later.networkTeam)
    }

    @Test
    fun anOlderAckForTheSameStateRenamesTheTeamWhileTheConsoleFollowsTheNetwork() {
        val c = case(ack(AckStatus.ACKNOWLEDGED, "Medical 1", 60), ack(AckStatus.ACKNOWLEDGED, "Medical 2", 55))
        assertEquals("Medical 2", c.team)
        assertFalse(c.localOnly)
    }

    @Test
    fun reopeningIsLocalUntilTheNetworkSaysSomethingNew() {
        val reopened = case(
            ack(AckStatus.ACKNOWLEDGED, "Medical 1", 60),
            ack(AckStatus.RESOLVED, "Medical 1", 300),
            decision(LogEvent.REOPEN, "", 400, "pacientul a revenit"),
        )
        assertEquals(CaseState.OPEN, reopened.state)
        assertEquals("", reopened.team)
        assertNull(reopened.closedAt, "an open case has no close time")
        assertTrue(reopened.localOnly)
        // the same RESOLVED from another phone isn't news; a higher status is
        val stillOpen = case(
            ack(AckStatus.RESOLVED, "Medical 1", 300),
            decision(LogEvent.REOPEN, "", 400),
            ack(AckStatus.RESOLVED, "Medical 2", 290),
        )
        assertEquals(CaseState.OPEN, stillOpen.state)
        val resolvedLater = case(
            ack(AckStatus.ACKNOWLEDGED, "Medical 1", 60),
            decision(LogEvent.REOPEN, "", 70),
            ack(AckStatus.RESOLVED, "Medical 1", 300),
        )
        assertEquals(CaseState.CLOSED, resolvedLater.state)
        assertFalse(resolvedLater.localOnly)
    }

    @Test
    fun unfoundedClosesWithItsOwnWordEverywhere() {
        val c = case(
            ack(AckStatus.RECEIVED, "Control", 30, own = true),
            decision(LogEvent.UNFOUNDED, "Control", 200, "nimeni la fața locului"),
            ack(AckStatus.RESOLVED, "Control", 200, own = true),
            ack(AckStatus.UNFOUNDED, "Control", 202, own = true),
        )
        assertEquals(CaseState.CLOSED, c.state)
        assertTrue(c.unfounded)
        assertFalse(c.localOnly)
        assertEquals(AckStatus.UNFOUNDED, c.network)
        assertEquals(200L, c.secondsTo(c.closedAt), "closed by the decision, not by its ACKs seconds later")
        val fromAnotherConsole = case(ack(AckStatus.ACKNOWLEDGED, "Medical 1", 60), ack(AckStatus.UNFOUNDED, "Control 2", 300))
        assertTrue(fromAnotherConsole.unfounded, "the network says so, without a decision here")
        val reopened = case(
            decision(LogEvent.UNFOUNDED, "Control", 200),
            ack(AckStatus.UNFOUNDED, "Control", 201, own = true),
            decision(LogEvent.REOPEN, "", 300),
        )
        assertFalse(reopened.unfounded)
        assertEquals(CaseState.OPEN, reopened.state)
    }

    @Test
    fun aReopenOnTheNetworkStartsAnEpochThatOldAcksCannotClose() {
        val c = case(
            ack(AckStatus.ACKNOWLEDGED, "Medical 1", 60),
            ack(AckStatus.CLOSED_BY_CONTROL, "Control", 300, own = true),
            decision(LogEvent.REOPEN, "", 400, "a căzut din nou"),
            ack(AckStatus.REOPENED, "Control", 400, own = true, epoch = 1),
            ack(AckStatus.CANCELLED, "Medical 2", 310),
        )
        assertEquals(CaseState.OPEN, c.state)
        assertFalse(c.localOnly, "the field sees it reopened too")
        assertEquals(1, c.epoch)
        assertNull(c.closedAt)
        val taken = case(
            ack(AckStatus.CLOSED_BY_CONTROL, "Control", 300, own = true),
            ack(AckStatus.REOPENED, "Control", 400, own = true, epoch = 1),
            ack(AckStatus.ON_SCENE, "Medical 2", 500, epoch = 1),
        )
        assertEquals(CaseState.ASSIGNED, taken.state)
        assertEquals("Medical 2", taken.team)
        assertEquals(CaseState.ASSIGNED, ConsoleView.stateOf(AckStatus.HANDED_OVER))
        assertEquals(CaseState.OPEN, ConsoleView.stateOf(AckStatus.REOPENED))
    }

    @Test
    fun aCancelToCheckShowsUntilTheOperatorDecidesOrTheIncidentIsReopened() {
        val request = LogEntry(t0 + 100_000, LogEvent.CANCEL_REQUEST, id)
        assertTrue(case(ack(AckStatus.RECEIVED, "Control", 30), request).cancelRequested)
        assertTrue(case(stored = incident().copy(cancelRequested = true)).cancelRequested, "from the incident before the log has it")
        val confirmed = case(
            ack(AckStatus.RECEIVED, "Control", 30), request, decision(LogEvent.CANCEL, "Control", 120),
            ack(AckStatus.CANCELLED, "Control", 120, own = true),
        )
        assertFalse(confirmed.cancelRequested)
        assertEquals(CaseState.CLOSED, confirmed.state)
        assertEquals(120L, confirmed.secondsTo(confirmed.closedAt))
        val reopened = case(
            ack(AckStatus.RECEIVED, "Control", 30), request, ack(AckStatus.REOPENED, "Control", 200, epoch = 1),
        )
        assertFalse(reopened.cancelRequested, "a request from the old epoch is settled")
    }

    @Test
    fun incidentOpenedBeforeTheConsoleRoleStillShowsWithItsNetworkState() {
        val stored = incident(AckStatus.ACKNOWLEDGED, "Medical 1")
        val c = ConsoleView.case(emptyList(), stored, grid)!!
        assertEquals(CaseState.ASSIGNED, c.state)
        assertEquals("Medical 1", c.team)
        assertNull(c.acknowledgedAt, "the log doesn't know when")
        assertEquals("A leșinat", c.report.description)
        assertNull(ConsoleView.case(listOf(ack(AckStatus.RECEIVED, "x", 1)), null, grid), "an ACK alone says nothing about the incident")
        val all = ConsoleView.cases(listOf(ack(AckStatus.RECEIVED, "x", 1)), listOf(stored), grid)
        assertEquals(listOf(id), all.keys.toList())
    }

    @Test
    fun cancelledByTheReporterIsClosed() {
        val c = case(ack(AckStatus.RECEIVED, "Control", 30, own = true), ack(AckStatus.CANCELLED, "Control", 90, own = true))
        assertEquals(CaseState.CLOSED, c.state)
        assertEquals(AckStatus.CANCELLED, c.network)
        assertNull(c.acknowledgedAt)
    }

    // --- writing the log ---

    private class FixedClock(var now: Long) : Clock {
        override fun wallMs() = now
        override fun monoMs() = now
    }

    private fun signed(status: Int, team: String, atSec: Long, epoch: Int = 0) =
        IncidentAck(id.hexToBytes(), status, (t0 / 1000) + atSec, team, ByteArray(64) { 7 }, IncidentAck.extensions(0, epoch))

    @Test
    fun recorderAppendsTheReportBeforeTheAckSignedWhileOpeningIt() = runTest {
        val store = ConsoleLogStore(tmp.root / "console.json", backgroundScope)
        val clock = FixedClock(t0 + 20_000)
        var stored: StaffIncident? = null
        val recorder = ConsoleRecorder(store, grid, clock) { stored }
        stored = incident()
        recorder.sawAck(signed(AckStatus.RECEIVED, "Control", 20), own = true, hops = 0)
        recorder.sawIncidents(listOf(incident()))
        assertEquals(listOf(LogEvent.REPORT, LogEvent.ACK), store.value.entries.map { it.event })
        val ack = store.value.entries[1]
        assertEquals("07".repeat(64), ack.signature)
        assertEquals(t0 + 20_000, ack.signedAt)
        assertTrue(ack.own)

        clock.now += 60_000
        recorder.sawAck(signed(AckStatus.ACKNOWLEDGED, "Medical 1", 80), own = false, hops = 4)
        recorder.decide(id, LogEvent.RESOLVE, "Control", "  rezolvat prin stație ")
        recorder.exported()
        recorder.sawIncidents(listOf(incident()))
        assertEquals(listOf(LogEvent.REPORT, LogEvent.ACK, LogEvent.ACK, LogEvent.RESOLVE, LogEvent.EXPORT), store.value.entries.map { it.event })
        assertEquals(4, store.value.entries[2].hops)
        assertEquals("rezolvat prin stație", store.value.entries[3].reason)
        assertFailsWith<IllegalArgumentException> { recorder.decide(id, LogEvent.ACK) }

        // a restart doesn't log the same report twice
        store.flush()
        val again = ConsoleLogStore(tmp.root / "console.json", backgroundScope)
        ConsoleRecorder(again, grid, clock) { stored }.sawIncidents(listOf(incident()))
        assertEquals(5, again.value.entries.size)
    }

    @Test
    fun recorderLogsEachCancelToCheckOncePerEpochAndWhatTheReportSaid() = runTest {
        val store = ConsoleLogStore(tmp.root / "console.json", backgroundScope)
        val recorder = ConsoleRecorder(store, grid, FixedClock(t0 + 20_000)) { null }
        val staffReport = incident().copy(
            responds = Answer.NO, breathes = Answer.UNKNOWN, fromStaff = true, nickname = "Medical 1", people = 2, dangerContinues = true,
        )
        recorder.sawIncidents(listOf(staffReport))
        val report = store.value.entries.single()
        assertEquals(listOf(Answer.NO, Answer.UNKNOWN), listOf(report.responds, report.breathes))
        assertTrue(report.fromStaff && report.dangerContinues)
        assertEquals("Medical 1", report.team)
        assertEquals(2, report.people)

        val requested = staffReport.copy(cancelRequested = true)
        recorder.sawIncidents(listOf(requested))
        recorder.sawIncidents(listOf(requested))
        recorder.sawIncidents(listOf(requested.copy(epoch = 1)))
        assertEquals(
            listOf(LogEvent.REPORT, LogEvent.CANCEL_REQUEST, LogEvent.CANCEL_REQUEST),
            store.value.entries.map { it.event },
        )
        assertEquals(listOf(0, 1), store.value.entries.drop(1).map { it.epoch })
        recorder.sawAck(signed(AckStatus.REOPENED, "Control", 30, epoch = 1), own = true, hops = 0)
        assertEquals(1, store.value.entries.last().epoch)
    }

    // --- the field side of an assignment ---

    @Test
    fun aTeamTakesAnIncidentNamedForItOrForItsRole() {
        assertTrue(isAssignedTo("Medical 1", "Medical 1"))
        assertTrue(isAssignedTo("medical", "Medical 2"))
        assertFalse(isAssignedTo("Medical 1", "Medical 10"))
        assertFalse(isAssignedTo("Medical", "Medicale"))
        assertFalse(isAssignedTo("Securitate", "Medical 1"))
        assertFalse(isAssignedTo("", "Medical 1"))
        assertFalse(isAssignedTo("Medical 1", ""))
    }

    // --- the control role ---

    @Test
    fun controlRoleTravelsInTheStaffQrAndOlderRolesKeepTheirByte() {
        val card = StaffCard(ByteArray(32) { 1 }, ByteArray(32) { 2 }, StaffRole.CONTROL, "Control", "")
        val text = QrCodes.encodeStaff(card)
        assertEquals(StaffRole.CONTROL, QrCodes.decodeStaff(text)!!.role)
        fun roleByte(role: StaffRole) =
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(QrCodes.encodeStaff(StaffCard(ByteArray(32), ByteArray(32), role, "x", "")).removePrefix("SPS1."))[64].toInt()
        assertEquals(listOf(1, 2, 3), listOf(StaffRole.STAFF, StaffRole.ANCHOR, StaffRole.CONTROL).map(::roleByte))
        val unknown = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL).decode(text.removePrefix("SPS1.")).also { it[64] = 4 }
        assertNull(QrCodes.decodeStaff("SPS1." + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(unknown)))
        assertTrue(Role.CONTROL.hasStaffKeys && Role.STAFF.hasStaffKeys)
        assertFalse(Role.ANCHOR.hasStaffKeys || Role.PARTICIPANT.hasStaffKeys)
    }

    // --- phones the console heard ---

    @Test
    fun peerBookRemembersWhenEachPhoneWasLastHeard() {
        val wall = t0
        val mono = 100_000L
        val anchor = 0x11223344
        val staffNode = 0x5566778899aabbccL
        val first = PeerBook.update(
            emptyMap(),
            MeshState(
                seen = listOf(SeenPeer("aa", anchor, NodeFlags.ANCHOR, -60, mono - 5_000), SeenPeer("bb", null, 0, -70, mono)),
                links = listOf(LinkInfo(1, "cc", staffNode, null, NodeFlags.STAFF, true, mono - 60_000)),
            ),
            wall, mono,
        )
        assertEquals(2, first.size)
        assertEquals(wall - 5_000, first.getValue(anchor).lastHeardMs)
        val linked = first.getValue((staffNode ushr 32).toInt())
        assertTrue(linked.linked)
        assertEquals(wall, linked.lastHeardMs)

        // a minute later the scan forgot the anchor and the link closed; the book still knows both
        val later = PeerBook.update(first, MeshState(), wall + 60_000, mono + 60_000)
        assertEquals(wall - 5_000, later.getValue(anchor).lastHeardMs)
        assertFalse(later.getValue((staffNode ushr 32).toInt()).linked)
        assertEquals(listOf((staffNode ushr 32).toInt(), anchor), PeerBook.sorted(later).map { it.prefix })
        assertEquals(listOf((staffNode ushr 32).toInt(), anchor), PeerBook.sorted(first).map { it.prefix })
    }

    @Test
    fun aLinkedPhoneIsOneRowUnderThePrefixItAdvertises() {
        val rotating = 0x0bad_cafe
        val state = MeshState(
            seen = listOf(SeenPeer("aa", rotating, NodeFlags.STAFF, -60, 9_000)),
            links = listOf(LinkInfo(1, "c:aa", 0x5566778899aabbccL, null, NodeFlags.STAFF, false, 0, prefix = rotating)),
        )
        val book = PeerBook.update(emptyMap(), state, t0, 10_000)
        assertEquals(listOf(rotating), book.keys.toList())
        assertTrue(book.getValue(rotating).linked)
    }

    @Test
    fun aRoleShowsAsVerifiedOnlyOnceALinkProvedIt() {
        val anchor = 0x11223344
        fun heard(flags: Int, at: Long) = MeshState(seen = listOf(SeenPeer("aa", anchor, flags, -60, at)))
        val claimed = PeerBook.update(emptyMap(), heard(NodeFlags.ANCHOR, 9_000), t0, 10_000)
        assertFalse(claimed.getValue(anchor).verified)
        val link = LinkInfo(1, "aa", 0x1122334455667788L, null, NodeFlags.ANCHOR, true, 0, anchor, verified = true)
        val linked = PeerBook.update(claimed, MeshState(links = listOf(link)), t0 + 1_000, 11_000)
        assertTrue(linked.getValue(anchor).verified)
        // its advertising keeps the proof while it claims the same role
        assertTrue(PeerBook.update(linked, heard(NodeFlags.ANCHOR, 20_000), t0 + 10_000, 20_000).getValue(anchor).verified)
        assertFalse(PeerBook.update(linked, heard(NodeFlags.STAFF, 20_000), t0 + 10_000, 20_000).getValue(anchor).verified)
    }

    @Test
    fun peerBookKeepsTheMostRecentPhonesOnly() {
        val many = (0 until PeerBook.MAX_PEERS + 20).map { SeenPeer("a$it", it, 0, -80, it.toLong()) }
        val book = PeerBook.update(emptyMap(), MeshState(seen = many), t0, 10_000)
        assertEquals(PeerBook.MAX_PEERS, book.size)
        assertFalse(0 in book, "the oldest go")
    }

    @Test
    fun aSilentAnchorIsNeverCrowdedOut() {
        val anchor = SeenPeer("anchor", 0x7fff_0000, NodeFlags.ANCHOR, -80, 0)
        val crowd = (1..PeerBook.MAX_PEERS + 20).map { SeenPeer("a$it", it, 0, -80, it.toLong()) }
        val book = PeerBook.update(emptyMap(), MeshState(seen = listOf(anchor) + crowd), t0, 10_000)
        assertEquals(PeerBook.MAX_PEERS, book.size)
        assertTrue(0x7fff_0000 in book, "the oldest phone heard, but an anchor")

        val rows = PeerBook.rows(book, max = 60)
        assertEquals(61, rows.size)
        assertEquals(0x7fff_0000, rows.first().prefix, "anchors first, past the cap for the others")
        assertEquals(60, rows.count { !it.anchor })
    }
}
