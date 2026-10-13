package ro.safetyplease.core.safety

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.LastFix
import ro.safetyplease.core.venue.GeoFix
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MINUTE = 60_000L

/** The check-in on the way: extend, cancel, arrive, and silence ends in exactly one alert. */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckinTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private class Sent(val friends: List<Long>, val deadlineSec: Long, val fix: LastFix?, val label: String)

    /** [life] is how long this phone's process lives: the test, unless it is ended by hand. */
    private class Rig(scope: TestScope, dir: TempDir, name: String = "checkin", life: CoroutineScope = scope.backgroundScope) {
        val clock = TestClock(scope.testScheduler)
        val friends = (1L..6L).map { Friend(it, "Prieten $it", "", "", 0) }
        val sent = mutableListOf<Sent>()
        val told = mutableListOf<Pair<List<Long>, String>>()
        val notices = mutableListOf<SafetyNotice>()
        var fix: GeoFix? = GeoFix(44.4271, 26.1031, 8f, clock.wallMs() - 5 * MINUTE)
        val store = JsonStore(dir.root / "$name.json", CheckinData.serializer(), CheckinData(), life)
        val manager = CheckinManager(
            life, store, clock, friends = { friends }, lastFix = { fix },
            alert = { to, deadline, lastFix, label ->
                sent += Sent(to, deadline, lastFix, label)
                to.size
            },
            tell = { to, text -> told += to to text },
            notify = { notices += it },
        ).also { it.start() }
        val current get() = store.value.current
    }

    @Test
    fun silenceSendsOneAlertTenMinutesAfterTheDeadlineAndNothingBefore() = runTest {
        val r = Rig(this, tmp)
        assertTrue(r.manager.begin("Gară", 30, listOf(1, 2)))
        val deadlineSec = (r.clock.wallMs() + 30 * MINUTE) / 1000

        advanceTimeBy(29 * MINUTE)
        assertTrue(r.notices.none { it is SafetyNotice.CheckinDue }, "nothing before the time")
        advanceTimeBy(2 * MINUTE)
        assertEquals(listOf<SafetyNotice>(SafetyNotice.CheckinCleared, SafetyNotice.CheckinDue("Gară")), r.notices, "the question at the deadline")
        assertTrue(r.sent.isEmpty(), "and still no packet")

        advanceTimeBy(8 * MINUTE)
        assertTrue(r.sent.isEmpty(), "ten minutes are the person's to answer")
        advanceTimeBy(2 * MINUTE)
        val alert = r.sent.single()
        assertEquals(listOf(1L, 2L), alert.friends)
        assertEquals(deadlineSec, alert.deadlineSec)
        assertEquals("Gară", alert.label)
        assertEquals(LastFix(44.4271, 26.1031, r.fix!!.timeMs / 1000, AccuracyClass.of(8.0)), alert.fix, "the last position the phone had")
        assertEquals(SafetyNotice.CheckinAlerted("Gară", 2), r.notices.last())
        assertEquals(CheckinPhase.ALERTED, r.current?.phase)

        advanceTimeBy(5 * 60 * MINUTE)
        assertEquals(1, r.sent.size, "exactly one, however long the silence lasts")
    }

    @Test
    fun arrivingBeforeTheDeadlineSendsAndAsksNothing() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Cazare", 30, listOf(1))
        advanceTimeBy(10 * MINUTE)
        r.manager.arrived()
        advanceTimeBy(60 * MINUTE)
        assertNull(r.current)
        assertTrue(r.sent.isEmpty())
        assertTrue(r.notices.none { it is SafetyNotice.CheckinDue || it is SafetyNotice.CheckinAlerted })
        assertTrue(r.told.isEmpty(), "friends who were never alarmed hear nothing")
    }

    @Test
    fun arrivingAfterTheQuestionStopsTheAlert() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Parcare", 20, listOf(1, 2))
        advanceTimeBy(25 * MINUTE)
        assertEquals(CheckinPhase.ASKED, r.current?.phase)
        r.manager.arrived("Am ajuns")
        advanceTimeBy(60 * MINUTE)
        assertTrue(r.sent.isEmpty())
        assertTrue(r.told.isEmpty())
        assertEquals(SafetyNotice.CheckinCleared, r.notices.last(), "the question goes away")
    }

    @Test
    fun extendingMovesTheDeadlineFromTheOldOneBeforeItAndFromNowAfterIt() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 30, listOf(1))
        advanceTimeBy(20 * MINUTE)
        assertTrue(r.manager.extend(15))
        advanceTimeBy(20 * MINUTE)
        assertTrue(r.notices.none { it is SafetyNotice.CheckinDue }, "45 minutes is the new deadline")
        advanceTimeBy(6 * MINUTE)
        assertTrue(r.notices.any { it is SafetyNotice.CheckinDue })

        // asked at 45, and at 50 the person says "+15": the new deadline is 65
        advanceTimeBy(4 * MINUTE)
        assertTrue(r.manager.extend(15))
        assertEquals(CheckinPhase.WAITING, r.current?.phase)
        advanceTimeBy(14 * MINUTE)
        assertEquals(CheckinPhase.WAITING, r.current?.phase)
        advanceTimeBy(2 * MINUTE)
        assertEquals(CheckinPhase.ASKED, r.current?.phase)
        assertTrue(r.sent.isEmpty(), "the old grace period is gone with the old deadline")
        advanceTimeBy(11 * MINUTE)
        assertEquals(1, r.sent.size)
    }

    @Test
    fun cancellingSendsNothingAndAnAlertedCheckinCannotBeExtended() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 5, listOf(1))
        advanceTimeBy(2 * MINUTE)
        r.manager.cancel()
        advanceTimeBy(60 * MINUTE)
        assertTrue(r.sent.isEmpty())
        assertFalse(r.manager.extend(15), "nothing to extend")

        r.manager.begin("Gară", 5, listOf(1, 3))
        advanceTimeBy(20 * MINUTE)
        assertEquals(1, r.sent.size)
        assertFalse(r.manager.extend(30), "the friends were told already")
        r.manager.arrived("Am ajuns la Gară, sunt bine.")
        assertEquals(listOf(listOf(1L, 3L) to "Am ajuns la Gară, sunt bine."), r.told, "now they hear that it is fine")
        assertNull(r.current)
    }

    @Test
    fun aCheckinNeedsAPlaceATimeAndFromOneToFiveFriendsWeKnow() = runTest {
        val r = Rig(this, tmp)
        assertFalse(r.manager.begin("  ", 30, listOf(1)), "no place")
        assertFalse(r.manager.begin("Gară", 0, listOf(1)), "no time")
        assertFalse(r.manager.begin("Gară", CheckinManager.MAX_MINUTES + 1, listOf(1)), "more than twelve hours")
        assertFalse(r.manager.begin("Gară", 30, emptyList()), "nobody to tell")
        assertFalse(r.manager.begin("Gară", 30, listOf(99)), "a stranger")
        assertFalse(r.manager.begin("Gară", 30, listOf(1, 2, 3, 4, 5, 6)), "six friends")
        assertNull(r.current)
        assertTrue(r.manager.begin("Gară", 30, listOf(5, 5, 1, 99)))
        assertEquals(listOf(5L, 1L), r.current?.friends, "a friend once, a stranger not at all")
        assertTrue(r.manager.begin("Cazare cu un nume foarte lung, mai lung de treizeci și doi de octeți", 30, listOf(1)))
        assertTrue(r.current!!.label.encodeToByteArray().size <= 32, "what the wire takes")
    }

    @Test
    fun placesAreRememberedNewestFirstWithoutRepeats() = runTest {
        val r = Rig(this, tmp)
        for (place in listOf("Gară", "Cazare", "Gară", "Poarta 3", "Cortul 12", "Parcare", "Rulota")) r.manager.begin(place, 30, listOf(1))
        assertEquals(listOf("Rulota", "Parcare", "Cortul 12", "Poarta 3", "Gară"), r.store.value.places)
    }

    @Test
    fun aCheckinSurvivesARestartAndStillSendsOnce() = runTest {
        val firstLife = CoroutineScope(backgroundScope.coroutineContext + Job())
        val first = Rig(this, tmp, "restart", firstLife)
        first.manager.begin("Gară", 30, listOf(1, 2))
        advanceTimeBy(31 * MINUTE)
        first.store.flush()
        val saved = first.store.value
        firstLife.cancel()

        // the process dies; a new one reads what was kept
        val secondLife = CoroutineScope(backgroundScope.coroutineContext + Job())
        val again = Rig(this, tmp, "restart", secondLife)
        assertEquals(saved.current, again.current)
        assertEquals(CheckinPhase.ASKED, again.current?.phase)
        advanceTimeBy(15 * MINUTE)
        assertEquals(1, again.sent.size)
        again.store.flush()
        secondLife.cancel()

        val afterAlert = Rig(this, tmp, "restart")
        advanceTimeBy(60 * MINUTE)
        assertTrue(afterAlert.sent.isEmpty(), "an alert once sent is not sent by the next process")
        assertEquals(CheckinPhase.ALERTED, afterAlert.current?.phase)
    }

    @Test
    fun withoutAPositionTheAlertSaysSo() = runTest {
        val r = Rig(this, tmp)
        r.fix = null
        r.manager.begin("Cazare", 5, listOf(1))
        advanceTimeBy(20 * MINUTE)
        assertNull(r.sent.single().fix)
    }

    // --- the friends' side ---

    private fun missed(msgId: Long, label: String = "Gară", fix: LastFix? = LastFix(44.0, 26.0, 1_760_000_000L, 2)) =
        Inner.CheckinMissed(msgId, null, 1_760_000_600L, fix, label)

    @Test
    fun aFriendsAlertIsKeptOnceWithTheirLastPositionAndTheFriendIsNamed() = runTest {
        val r = Rig(this, tmp)
        r.manager.onFriendMissed(2, missed(7), hops = 1)
        r.manager.onFriendMissed(2, missed(7), hops = 3)
        val alert = r.store.value.alerts.single()
        assertEquals(2L, alert.sender)
        assertEquals("Gară", alert.label)
        assertEquals(44.0, alert.lat)
        assertEquals(1_760_000_000L, alert.fixedAt)
        assertEquals(1, alert.hops, "the first copy")
        assertEquals(listOf<SafetyNotice>(SafetyNotice.FriendSilent("Prieten 2", "Gară")), r.notices, "told once")

        r.manager.onFriendMissed(2, missed(8, fix = null), hops = 0)
        assertNull(r.store.value.alerts.first().lat, "an alert without a position is still an alert")
        r.manager.dismissAlert(2, 7)
        assertEquals(listOf(8L), r.store.value.alerts.map { it.msgId })
    }

    @Test
    fun onlyTheLastTenAlertsAreKept() = runTest {
        val r = Rig(this, tmp)
        for (i in 1L..15L) r.manager.onFriendMissed(1, missed(i), 0)
        assertEquals((6L..15L).reversed().toList(), r.store.value.alerts.map { it.msgId })
    }

    @Test
    fun theStateIsPlainJson() {
        val data = CheckinData(Checkin("Gară", 1, 2, listOf(3)), listOf("Cazare"), emptyList())
        assertEquals(data, Json.decodeFromString(CheckinData.serializer(), Json.encodeToString(CheckinData.serializer(), data)))
    }
}
