package ro.safetyplease.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What friends share with us: the receiver ends it, with or without a STOP. */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionStoreTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val friend = 7L

    private fun record(fixAt: Long, seq: Int, receivedAt: Long = fixAt, expires: Long = Long.MAX_VALUE) =
        PositionRecord(friend, 44.0, 26.0, 1, 0, null, fixAt, receivedAt, expires, seq, 1)

    @Test
    fun aSessionEndsAtItsExpiryEvenWithoutStop() {
        val start = 1_000_000L
        var data = PositionData().startedBy(friend, 30, 0, start)
        val expires = start + 30 * 60_000L
        assertEquals(expires, data.sessionOf(friend, start)!!.expiresAtMs)
        data = data.withPosition(record(start + 60_000, 1, expires = expires))
        assertSame(data, data.pruned(expires - 1), "nothing to end yet")

        val after = data.pruned(expires)
        assertTrue(after.records.isEmpty())
        assertNull(after.sessionOf(friend, expires))
        assertEquals(listOf(LastSeen(friend, start + 60_000)), after.lastSeen, "only when it was last seen stays")
    }

    @Test
    fun noSessionOrPositionOutlivesTwoHours() {
        val start = 5_000_000L
        val data = PositionData().startedBy(friend, 600, 0, start)
        assertEquals(start + 2 * 3_600_000L, data.incoming.single().expiresAtMs)
        val kept = PositionData().withPosition(record(start, 1, receivedAt = start))
        assertEquals(start + PositionData.MAX_RECORD_MS, kept.records.single().expiresAtMs)
        assertTrue(PositionData().startedBy(friend, 0, 0, start).incoming.isEmpty(), "a START of 0 minutes is a stop")
    }

    @Test
    fun stopIsSilentAndRenewMovesTheEnd() {
        val start = 0L
        val data = PositionData().startedBy(friend, 10, 0, start).withPosition(record(1_000, 1, expires = 600_000))
        val renewed = data.startedBy(friend, 60, 0, 300_000)
        assertEquals(300_000 + 3_600_000L, renewed.records.single().expiresAtMs)

        val stopped = renewed.stoppedBy(friend)
        assertTrue(stopped.records.isEmpty() && stopped.incoming.isEmpty())
        assertEquals(listOf(LastSeen(friend, 1_000)), stopped.lastSeen)
        assertSame(stopped, stopped.stoppedBy(friend))
    }

    @Test
    fun theNewerFixWinsWhateverTheOrderOfArrival() {
        val data = PositionData().withPosition(record(120_000, 5))
        assertSame(data, data.withPosition(record(60_000, 9)), "older")
        assertEquals(6, data.withPosition(record(120_000, 6)).records.single().seq)
        assertSame(data, data.withPosition(record(120_000, 4)), "the same minute, an earlier seq")
        assertEquals(1, PositionData().withPosition(record(0, 250)).withPosition(record(0, 1)).records.single().seq, "seq wraps")
        assertEquals(0L, record(10_000, 1).ageMs(5_000))
        assertEquals(50_000L, record(10_000, 1).ageMs(60_000))
    }

    @Test
    fun aFixFromAClockAheadCountsAsItsArrivalAndHoldsNothingBack() {
        val now = 10_000_000L
        val ahead = PositionData().withPosition(record(now + 30 * 60_000L, 5, receivedAt = now))
        assertEquals(now, ahead.records.single().fixAtMs, "as if taken when it came")
        val next = ahead.withPosition(record(now + 60_000, 6, receivedAt = now + 60_000))
        assertEquals(6, next.records.single().seq, "the next fix, from a clock put right, is newer")
    }

    @Test
    fun anOlderLookingFixIsDroppedOnlyWhileTwoCopiesCanCross() {
        val now = 10_000_000L
        val data = PositionData().withPosition(record(now, 5, receivedAt = now))
        assertSame(data, data.withPosition(record(now - 10 * 60_000L, 6, receivedAt = now + 30_000)))
        // the sender's clock went back: what comes well after is its latest all the same
        val later = data.withPosition(record(now - 10 * 60_000L, 7, receivedAt = now + PositionData.REORDER_MS + 1_000))
        assertEquals(7, later.records.single().seq)
    }

    @Test
    fun theMeetingSeqIsKeptWithThePositions() = runTest {
        val file = tmp.root / "seq.json"
        val store = PositionStore.open(file, backgroundScope)
        assertEquals(PositionData.MEETING_SEQ_START, store.value.meetingSeq)
        store.update { it.copy(meetingSeq = 255) }
        advanceTimeBy(1_000)
        assertEquals(255, PositionStore.open(file, backgroundScope).value.meetingSeq)
    }

    @Test
    fun forgettingSomeoneLeavesNothingOfTheirs() {
        val data = PositionData().startedBy(friend, 10, 0, 0).withPosition(record(1, 1)).withReader(friend)
        val gone = data.forget(friend)
        assertEquals(PositionData(), gone)
        assertSame(gone, gone.forget(friend))
    }

    @Test
    fun theStoreKeepsItAcrossARestart() = runTest {
        val file = tmp.root / "positions.json"
        val store = PositionStore.open(file, backgroundScope)
        store.update { it.startedBy(friend, 10, 0, 0).withReader(friend).copy(autoAnswer = true) }
        val before = store.value
        store.update { it.withReader(friend) }
        assertSame(before, store.value, "no change, no write")
        advanceTimeBy(1_000)
        assertTrue(file.readText().contains("\"schemaVersion\":${PositionStore.SCHEMA_VERSION}"))

        val again = PositionStore.open(file, backgroundScope)
        assertEquals(before, again.value)
        assertEquals(PositionData(), PositionStore.inMemory().value)
    }
}
