package ro.safetyplease.core.safety

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.LastFix
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MINUTE = 60_000L

/** The test scheduler's time, with a wall clock that can be stepped and a hook on its next reading. */
private class StepClock(private val scheduler: TestCoroutineScheduler) : Clock {
    var step = 0L
    var onNextWall: (() -> Unit)? = null

    override fun wallMs(): Long {
        onNextWall?.let {
            onNextWall = null
            it()
        }
        return 1_760_000_000_000L + scheduler.currentTime + step
    }

    override fun monoMs(): Long = scheduler.currentTime
}

/** The check-in on the way: extend, cancel, arrive, and silence ends in exactly one alert. */
@OptIn(ExperimentalCoroutinesApi::class)
class CheckinTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private class Sent(val msgId: Long, val friends: List<Long>, val deadlineSec: Long, val fix: LastFix?, val label: String, val atMono: Long)

    /** [life] is how long this phone's process lives: the test, unless it is ended by hand. */
    private class Rig(scope: TestScope, dir: TempDir, name: String = "checkin", life: CoroutineScope = scope.backgroundScope, seed: CheckinData? = null) {
        val clock = StepClock(scope.testScheduler)
        val friends = (1L..6L).map { Friend(it, "Prieten $it", "", "", 0) }
        val sent = mutableListOf<Sent>()
        val told = mutableListOf<Pair<List<Long>, String>>()
        val notices = mutableListOf<SafetyNotice>()
        val askedAtMono = mutableListOf<Long>()
        val withdrawn = mutableListOf<Long>()

        /** The friends the alert hasn't reached yet, as the outbox would say. */
        var unreached = emptyList<Long>()

        /** Of the chosen, those still friends when the alert is queued. */
        var accepted: (List<Long>) -> List<Long> = { it }
        var fix: GeoFix? = GeoFix(44.4271, 26.1031, 8f, clock.wallMs() - 5 * MINUTE)
        val store = JsonStore(dir.root / "$name.json", CheckinData.serializer(), CheckinData(), life).also { s -> seed?.let { d -> s.update { d } } }
        val manager = CheckinManager(
            life, store, clock, friends = { friends }, lastFix = { fix },
            // it suspends as ChatManager's does, handing the work to the same thread, so other coroutines run meanwhile
            alert = { id, to, deadline, lastFix, label ->
                life.async {
                    sent += Sent(id, to, deadline, lastFix, label, clock.monoMs())
                    accepted(to)
                }.await()
            },
            withdraw = { id ->
                withdrawn += id
                unreached
            },
            tell = { to, text -> told += to to text },
            notify = {
                notices += it
                if (it is SafetyNotice.CheckinDue) askedAtMono += clock.monoMs()
            },
        ).also { it.start() }
        val current get() = store.value.current
    }

    @Test
    fun silenceSendsOneAlertTenMinutesAfterTheQuestionAndNothingBefore() = runTest {
        val r = Rig(this, tmp)
        assertTrue(r.manager.begin("Gară", 30, listOf(1, 2)))
        val started = r.current!!.startedAt
        val deadlineSec = (r.clock.wallMs() + 30 * MINUTE) / 1000

        advanceTimeBy(29 * MINUTE)
        assertTrue(r.notices.none { it is SafetyNotice.CheckinDue }, "nothing before the time")
        advanceTimeBy(2 * MINUTE)
        assertEquals(listOf<SafetyNotice>(SafetyNotice.CheckinCleared, SafetyNotice.CheckinDue("Gară")), r.notices, "the question at the deadline")
        assertEquals(started + 30 * MINUTE, r.current?.askedAt, "when it was asked is kept")
        assertTrue(r.sent.isEmpty(), "and still no packet")

        advanceTimeBy(8 * MINUTE)
        assertTrue(r.sent.isEmpty(), "ten minutes are the person's to answer")
        advanceTimeBy(2 * MINUTE)
        val alert = r.sent.single()
        assertEquals(CheckinManager.alertId(started), alert.msgId)
        assertEquals(listOf(1L, 2L), alert.friends)
        assertEquals(deadlineSec, alert.deadlineSec)
        assertEquals("Gară", alert.label)
        assertEquals(LastFix(44.4271, 26.1031, r.fix!!.timeMs / 1000, AccuracyClass.of(8.0)), alert.fix, "the last position the phone had")
        assertEquals(SafetyNotice.CheckinAlerted("Gară", 2), r.notices.last())
        assertEquals(CheckinPhase.ALERTED, r.current?.phase)
        assertEquals(listOf(1L, 2L), r.current?.alerted)

        advanceTimeBy(5 * 60 * MINUTE)
        assertEquals(1, r.sent.size, "exactly one, however long the silence lasts")
    }

    @Test
    fun theQueuedAlertStaysQueued() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 20, listOf(1, 2))
        advanceTimeBy(31 * MINUTE)
        assertEquals(1, r.sent.size)
        assertTrue(r.withdrawn.isEmpty(), "the watch that starts on the state the alert wrote takes nothing back")
        assertEquals(CheckinPhase.ALERTED, r.current?.phase)
        assertTrue(r.current!!.queued)
        assertEquals(listOf(1L, 2L), r.current?.alerted)
        assertEquals(1, r.notices.count { it is SafetyNotice.CheckinAlerted })
    }

    @Test
    fun theAlertComesAGraceAfterTheQuestionNeverSooner() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 7, listOf(1))
        advanceTimeBy(3 * 60 * MINUTE)
        assertEquals(CheckinManager.GRACE_MS, r.sent.single().atMono - r.askedAtMono.single())
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
        assertTrue(r.withdrawn.isEmpty(), "nothing to take back")
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
    fun arrivingAfterTheAlertTakesItBackAndReassuresOnlyTheFriendsItReached() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 5, listOf(1, 2, 3))
        val started = r.current!!.startedAt
        advanceTimeBy(20 * MINUTE)
        assertEquals(1, r.sent.size)
        r.unreached = listOf(3)
        r.manager.arrived("Am ajuns la Gară, sunt bine.")
        assertEquals(listOf(CheckinManager.alertId(started)), r.withdrawn, "the copy still waiting for friend 3 is taken out of the outbox")
        assertEquals(listOf(listOf(1L, 2L) to "Am ajuns la Gară, sunt bine."), r.told, "only those who got the alert hear the end of it")
        assertNull(r.current)
    }

    @Test
    fun anAnswerWhileTheAlertIsBeingQueuedTakesItBack() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 5, listOf(1))
        val id = CheckinManager.alertId(r.current!!.startedAt)
        r.accepted = { to -> to.also { r.manager.arrived() } }
        advanceTimeBy(20 * MINUTE)
        assertNull(r.current)
        assertEquals(id, r.withdrawn.last(), "what reached the outbox meanwhile is taken out")
        assertTrue(r.notices.none { it is SafetyNotice.CheckinAlerted })
    }

    @Test
    fun closingWithoutAMessageStillTakesTheAlertBack() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 5, listOf(1, 2))
        advanceTimeBy(20 * MINUTE)
        r.unreached = listOf(1, 2)
        r.manager.arrived()
        assertEquals(1, r.withdrawn.size)
        assertTrue(r.told.isEmpty())

        r.manager.begin("Cazare", 5, listOf(1))
        advanceTimeBy(20 * MINUTE)
        r.unreached = listOf(1)
        r.manager.arrived("Am ajuns")
        assertTrue(r.told.isEmpty(), "nobody got it, so nobody needs the reassurance")
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
        assertEquals(0L, r.current?.askedAt)
        advanceTimeBy(14 * MINUTE)
        assertEquals(CheckinPhase.WAITING, r.current?.phase)
        advanceTimeBy(2 * MINUTE)
        assertEquals(CheckinPhase.ASKED, r.current?.phase)
        assertTrue(r.sent.isEmpty(), "the old grace period is gone with the old question")
        advanceTimeBy(11 * MINUTE)
        assertEquals(1, r.sent.size)
    }

    @Test
    fun anExtensionThatLosesTheRaceToTheAlertChangesNothing() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 5, listOf(1))
        advanceTimeBy(14 * MINUTE)
        assertEquals(CheckinPhase.ASKED, r.current?.phase)
        // the alert goes between extend() reading the check-in and writing it back
        r.clock.onNextWall = { r.store.update { it.copy(current = it.current!!.copy(phase = CheckinPhase.ALERTED)) } }
        assertFalse(r.manager.extend(30))
        advanceTimeBy(MINUTE)
        assertEquals(CheckinPhase.ALERTED, r.current?.phase, "not back to waiting")
        assertEquals(1, r.sent.size)
        advanceTimeBy(2 * 60 * MINUTE)
        assertEquals(1, r.sent.size, "no second alert")
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
    fun anAlertForNobodySaysSo() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 5, listOf(1))
        // the friend was removed before the alert went
        r.accepted = { emptyList() }
        advanceTimeBy(20 * MINUTE)
        assertEquals(SafetyNotice.CheckinAlerted("Gară", 0), r.notices.last())
        assertEquals(0, r.current?.told)
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

        // the process dies; a new one reads what was kept and goes on with the question's ten minutes
        val secondLife = CoroutineScope(backgroundScope.coroutineContext + Job())
        val again = Rig(this, tmp, "restart", secondLife)
        assertEquals(saved.current, again.current)
        assertEquals(CheckinPhase.ASKED, again.current?.phase)
        advanceTimeBy(8 * MINUTE)
        assertTrue(again.sent.isEmpty())
        advanceTimeBy(2 * MINUTE)
        assertEquals(1, again.sent.size)
        assertTrue(again.notices.none { it is SafetyNotice.CheckinDue }, "within its window the question isn't asked twice")
        again.store.flush()
        secondLife.cancel()

        val afterAlert = Rig(this, tmp, "restart")
        advanceTimeBy(60 * MINUTE)
        assertTrue(afterAlert.sent.isEmpty(), "an alert once sent is not sent by the next process")
        assertEquals(CheckinPhase.ALERTED, afterAlert.current?.phase)
    }

    @Test
    fun aPhoneThatWasOffPastTheDeadlineAndTheGraceAsksFirst() = runTest {
        val firstLife = CoroutineScope(backgroundScope.coroutineContext + Job())
        val first = Rig(this, tmp, "off", firstLife)
        first.manager.begin("Cazare", 30, listOf(1))
        advanceTimeBy(5 * MINUTE)
        first.store.flush()
        firstLife.cancel()

        // off from minute 5 to minute 60: past the deadline and its ten minutes
        advanceTimeBy(55 * MINUTE)
        val again = Rig(this, tmp, "off")
        advanceTimeBy(1)
        assertEquals(listOf<SafetyNotice>(SafetyNotice.CheckinDue("Cazare")), again.notices, "asked as soon as the phone is back")
        assertEquals(again.clock.wallMs() - 1, again.current?.askedAt)
        advanceTimeBy(9 * MINUTE)
        assertTrue(again.sent.isEmpty(), "the person gets the whole ten minutes to answer")
        advanceTimeBy(MINUTE)
        assertEquals(1, again.sent.size)
    }

    @Test
    fun aQuestionLeftUnansweredByAProcessThatDiedLongAgoIsAskedAgain() = runTest {
        val firstLife = CoroutineScope(backgroundScope.coroutineContext + Job())
        val first = Rig(this, tmp, "asked", firstLife)
        first.manager.begin("Gară", 30, listOf(1))
        advanceTimeBy(32 * MINUTE)
        assertEquals(CheckinPhase.ASKED, first.current?.phase)
        first.store.flush()
        firstLife.cancel()

        advanceTimeBy(3 * 60 * MINUTE)
        val again = Rig(this, tmp, "asked")
        advanceTimeBy(1)
        assertEquals(SafetyNotice.CheckinDue("Gară"), again.notices.single(), "a fresh question")
        assertTrue(again.sent.isEmpty())
        advanceTimeBy(CheckinManager.GRACE_MS)
        assertEquals(1, again.sent.size)
        assertEquals(CheckinManager.GRACE_MS, again.sent.single().atMono - again.askedAtMono.single())
    }

    @Test
    fun aWallClockThatStepsNeitherAsksNorAlertsEarly() = runTest {
        val r = Rig(this, tmp)
        r.manager.begin("Gară", 30, listOf(1))
        advanceTimeBy(5 * MINUTE)
        r.clock.step = 2 * 60 * MINUTE
        advanceTimeBy(MINUTE)
        assertTrue(r.notices.none { it is SafetyNotice.CheckinDue }, "two hours ahead on the wall, six minutes in fact")
        advanceTimeBy(25 * MINUTE)
        assertEquals(CheckinPhase.ASKED, r.current?.phase)

        r.clock.step = 5 * 60 * MINUTE
        advanceTimeBy(5 * MINUTE)
        assertTrue(r.sent.isEmpty(), "the question's ten minutes run on the monotonic clock")
        advanceTimeBy(5 * MINUTE)
        assertEquals(1, r.sent.size)
    }

    @Test
    fun anAlertThatDidNotReachTheOutboxGoesAfterARestartWithTheSameMsgId() = runTest {
        val checkin = Checkin("Gară", 1_760_000_000_000L, 1_760_000_600_000L, listOf(1, 2), CheckinPhase.ALERTED, askedAt = 1_760_000_600_000L)
        val r = Rig(this, tmp, "crash", seed = CheckinData(checkin))
        advanceTimeBy(MINUTE)
        val sent = r.sent.single()
        assertEquals(CheckinManager.alertId(checkin.startedAt), sent.msgId, "the msgId comes from the check-in, so a friend keeps one copy")
        assertTrue(r.current!!.queued)
        assertEquals(SafetyNotice.CheckinAlerted("Gară", 2), r.notices.single())
        assertTrue(CheckinManager.alertId(checkin.startedAt) != CheckinManager.alertId(checkin.startedAt + 1))
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
        r.manager.onFriendMissed(2, missed(7))
        advanceTimeBy(MINUTE)
        r.manager.onFriendMissed(2, missed(7))
        val alert = r.store.value.alerts.single()
        assertEquals(2L, alert.sender)
        assertEquals("Gară", alert.label)
        assertEquals(44.0, alert.lat)
        assertEquals(1_760_000_000L, alert.fixedAt)
        assertEquals(1_760_000_000_000L, alert.receivedAt, "when the first copy came")
        assertEquals(
            listOf<SafetyNotice>(SafetyNotice.FriendSilent("Prieten 2", "Gară", 1_760_000_600_000L, 1_760_000_000_000L)), r.notices,
            "told once, with the deadline and the position's time",
        )

        r.manager.onFriendMissed(2, missed(8, fix = null))
        assertNull(r.store.value.alerts.first().lat, "an alert without a position is still an alert")
        assertEquals(0L, (r.notices.last() as SafetyNotice.FriendSilent).fixedAtMs)
        r.manager.dismissAlert(2, 7)
        assertEquals(listOf(8L), r.store.value.alerts.map { it.msgId })
    }

    @Test
    fun onlyTheLastTenAlertsAreKept() = runTest {
        val r = Rig(this, tmp)
        for (i in 1L..15L) r.manager.onFriendMissed(1, missed(i))
        assertEquals((6L..15L).reversed().toList(), r.store.value.alerts.map { it.msgId })
    }

    @Test
    fun theStateIsPlainJson() {
        val data = CheckinData(Checkin("Gară", 1, 2, listOf(3), CheckinPhase.ALERTED, 5, listOf(3), queued = true), listOf("Cazare"), emptyList())
        assertEquals(data, Json.decodeFromString(CheckinData.serializer(), Json.encodeToString(CheckinData.serializer(), data)))
    }
}
