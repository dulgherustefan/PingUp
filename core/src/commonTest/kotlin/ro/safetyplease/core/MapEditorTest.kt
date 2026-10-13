package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.control.MapEditor
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The console's map editor: changes close together go out as one map state, at most one every 30 seconds. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapEditorTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val venue = Venue(
        "test", Bounds(44.0, 26.0, 44.01, 26.01),
        (0 until 80).map { i -> Zone("z$i", "Zona $i", "#336699", listOf(GeoPoint(44.0, 26.0), GeoPoint(44.0, 26.001), GeoPoint(44.001, 26.0))) },
    )

    private class Setup(val clock: TestClock, val event: TestEvent, val console: TestPhone, val phone: TestPhone, val editor: MapEditor)

    private fun TestScope.setup(shiftUntil: Long = TestEvent.DAY): Setup {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val console = TestPhone("K", this, net, clock, tmp.root, event, 700)
        console.activate(event.shift(CertRole.CONTROL, "Control", until = shiftUntil))
        val phone = TestPhone("A", this, net, clock, tmp.root, event, 701)
        net.start()
        advanceTimeBy(30_000)
        return Setup(clock, event, console, phone, MapEditor(backgroundScope, venue, clock, console.notices))
    }

    private fun TestPhone.mapStates(clock: TestClock): List<Notice> =
        notices.board.value.active(clock.wallMs() / 1000).map { it.second }.filter { it.topic == NoticeTopic.MAP_STATE }

    private fun TestPhone.mapState(clock: TestClock): MapState = MapState.of(notices.board.value, venue, clock.wallMs() / 1000)

    @Test
    fun changesCloseTogetherGoOutAsOneSnapshotThatEveryPhoneReadsBack() = runTest {
        val s = setup()
        s.editor.set("z0", ZoneState.CLOSED)
        advanceTimeBy(1_000)
        s.editor.set("z5", ZoneState.CROWDED)
        advanceTimeBy(1_000)
        s.editor.set("z79", ZoneState.EXIT_ONLY)
        assertTrue(s.console.mapStates(s.clock).isEmpty(), "nothing before the changes settle")
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        val sent = s.console.mapStates(s.clock).single()
        assertEquals(1L, sent.seq, "three changes, one map state")
        assertEquals(80, sent.extras.states!!.size)
        assertTrue(sent.silent)
        val expected = mapOf("z0" to ZoneState.CLOSED, "z5" to ZoneState.CROWDED, "z79" to ZoneState.EXIT_ONLY)
        assertEquals(expected, s.console.mapState(s.clock).zones)
        assertTrue(s.editor.edits.value.isEmpty())
        advanceTimeBy(15_000)
        assertEquals(expected, s.phone.mapState(s.clock).zones, "the bytes decode the same on another phone")
        assertTrue(sent.encode().size <= 480, "80 zones fit a payload")
    }

    @Test
    fun aSecondSnapshotWaitsThirtySecondsAfterTheFirst() = runTest {
        val s = setup()
        s.editor.set("z1", ZoneState.CLOSED)
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        assertEquals(1, s.console.mapStates(s.clock).size)
        advanceTimeBy(2_000)
        s.editor.set("z2", ZoneState.CLOSED)
        s.editor.set("z1", ZoneState.OPEN)
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        assertEquals(1L, s.console.mapStates(s.clock).single().seq, "too soon after the first")
        assertTrue(s.editor.dueInMs()!! in MapEditor.MIN_GAP_MS - 7_000..MapEditor.MIN_GAP_MS - 5_000, "due 30 s after the first")
        advanceTimeBy(MapEditor.MIN_GAP_MS)
        val second = s.console.mapStates(s.clock).single()
        assertEquals(2L, second.seq)
        assertEquals(mapOf("z2" to ZoneState.CLOSED), s.console.mapState(s.clock).zones)
    }

    @Test
    fun aChangeTakenBackBeforeItLeavesSendsNothing() = runTest {
        val s = setup()
        s.editor.set("z3", ZoneState.CLOSED)
        advanceTimeBy(1_000)
        s.editor.set("z3", ZoneState.OPEN)
        advanceTimeBy(MapEditor.MIN_GAP_MS)
        assertTrue(s.console.mapStates(s.clock).isEmpty())
        assertNull(s.editor.dueInMs())
    }

    @Test
    fun openingEverythingIsOneMoreSnapshot() = runTest {
        val s = setup()
        s.editor.set("z3", ZoneState.CLOSED)
        s.editor.set("z4", ZoneState.CROWDED)
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        advanceTimeBy(MapEditor.MIN_GAP_MS)
        s.editor.openAll()
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        assertEquals(2L, s.console.mapStates(s.clock).single().seq)
        assertEquals(MapState.OPEN.zones, s.console.mapState(s.clock).zones)
    }

    @Test
    fun aPhoneWithoutAControlShiftSendsNoMapState() = runTest {
        val s = setup()
        val editor = MapEditor(backgroundScope, venue, s.clock, s.phone.notices)
        editor.set("z1", ZoneState.CLOSED)
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        assertTrue(s.phone.mapStates(s.clock).isEmpty())
        assertEquals(mapOf("z1" to ZoneState.CLOSED), editor.edits.value, "kept, not lost")
    }

    @Test
    fun anOperatorStillEditingHoldsTheFirstChangeBackAtMostTenSeconds() = runTest {
        val s = setup()
        for (i in 0 until 8) {
            s.editor.set("z$i", ZoneState.CLOSED)
            advanceTimeBy(2_000)
        }
        val sent = s.console.mapStates(s.clock).single()
        assertTrue(sent.extras.states!!.take(5).all { it == ZoneState.CLOSED }, "out by ten seconds after the first change")
    }

    @Test
    fun aMapStateThatStillClosesSomethingIsSignedAgainBeforeItExpires() = runTest {
        val s = setup()
        s.editor.set("z2", ZoneState.CLOSED)
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        val first = s.console.mapStates(s.clock).single()
        advanceTimeBy((MapEditor.VALID_S - MapEditor.REFRESH_BEFORE_S) * 1000 - 60_000)
        assertEquals(first.seq, s.console.mapStates(s.clock).single().seq, "not yet")
        advanceTimeBy(MapEditor.REFRESH_CHECK_MS + 120_000)
        val again = s.console.mapStates(s.clock).single()
        assertEquals(first.seq + 1, again.seq)
        assertEquals(first.extras.states, again.extras.states)
        assertTrue(again.issued > first.issued + 10 * 3600 && again.validUntil - again.issued == MapEditor.VALID_S, "a fresh validity")
        assertEquals(mapOf("z2" to ZoneState.CLOSED), s.console.mapState(s.clock).zones)
    }

    @Test
    fun aMapStateWithEverythingOpenIsLeftToExpire() = runTest {
        val s = setup()
        s.editor.set("z2", ZoneState.CLOSED)
        advanceTimeBy(MapEditor.MIN_GAP_MS + 1_000)
        s.editor.openAll()
        advanceTimeBy(MapEditor.SETTLE_MS + 500)
        val open = s.console.mapStates(s.clock).single()
        advanceTimeBy(MapEditor.VALID_S * 1000 - 60_000)
        assertEquals(open.seq, s.console.mapStates(s.clock).single().seq, "nothing to keep closed, nothing sent")
    }

    @Test
    fun editsMadeWhileTheShiftLapsedGoOutOnceItIsRenewed() = runTest {
        val s = setup(shiftUntil = 60)
        // past the shift and the clock skew a certificate is allowed
        advanceTimeBy(12 * 60_000)
        s.editor.set("z1", ZoneState.CLOSED)
        advanceTimeBy(MapEditor.SETTLE_MS + MapEditor.RETRY_MS)
        assertTrue(s.console.mapStates(s.clock).isEmpty())
        s.console.activate(s.event.shift(CertRole.CONTROL, "Control"))
        advanceTimeBy(MapEditor.RETRY_MS + 1_000)
        assertEquals(mapOf("z1" to ZoneState.CLOSED), s.console.mapState(s.clock).zones)
        assertTrue(s.editor.edits.value.isEmpty())
    }

    @Test
    fun sentAtOnceBeforeAnEvacuation() = runTest {
        val s = setup()
        s.editor.set("z1", ZoneState.CLOSED)
        assertTrue(s.editor.sendNow())
        advanceTimeBy(100)
        assertEquals(mapOf("z1" to ZoneState.CLOSED), s.console.mapState(s.clock).zones)
    }

    @Test
    fun onlyAConsoleRunsTheEditorAndTooManyZonesAreRefusedQuietly() = runTest {
        val s = setup()
        val idle = MapEditor(backgroundScope, venue, s.clock, s.console.notices, flowOf(false))
        idle.set("z1", ZoneState.CLOSED)
        advanceTimeBy(MapEditor.MIN_GAP_MS)
        assertTrue(s.console.mapStates(s.clock).isEmpty())
        val huge = Venue(venue.name, venue.bounds, (0 until 300).map { i -> venue.zones[0].copy(id = "h$i") })
        val tooMany = MapEditor(backgroundScope, huge, s.clock, s.console.notices)
        assertFalse(tooMany.supported)
        tooMany.set("h1", ZoneState.CLOSED)
        tooMany.openAll()
        assertTrue(tooMany.edits.value.isEmpty())
    }
}
