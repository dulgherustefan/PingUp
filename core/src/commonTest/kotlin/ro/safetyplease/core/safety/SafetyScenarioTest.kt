package ro.safetyplease.core.safety

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.TestEvent
import ro.safetyplease.core.TestPhone
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.IncidentBody
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.InnerCodec
import ro.safetyplease.core.protocol.LastFix
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.GeoFix
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Report options and the check-in alert over the simulated mesh, phone to phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class SafetyScenarioTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    /** Missed check-ins still waiting for a friend to confirm them. */
    private fun TestPhone.alertsInOutbox() = chatStore.value.outbox.count { InnerCodec.decode(it.innerHex.hexToBytes()) is Inner.CheckinMissed }

    private fun draft(nickname: String?, extras: ReportExtras) = ReportDraft(
        IncidentCategory.HARASSMENT, Severity.MEDIUM, "camping", 43.9505, 28.6350, "Cineva m-a urmărit", nickname, extras = extras,
    )

    // --- report options ---

    @Test
    fun anAnonymousReportCarriesNoNicknameAndNoReplyKeyAndStaffKnowsNothingCanBeAnswered() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 800)
        val d = TestPhone("D", this, net, clock, tmp.root, event, 801)
        d.staffSecret = event.secret
        d.engine.setRole(staff = true, anchor = false)
        net.start()
        advanceTimeBy(30_000)

        // the user typed a nickname and switched "Anonim" on: the second wins
        assertTrue(a.incidents.report(draft("Ana", ReportOptions.extras(anonymous = true))))
        val mine = a.myReports.single()
        // what leaves the phone, as staff opens it
        val body = IncidentBody.decode(event.staffCrypto.openReport(mine.sealed.hexToBytes(), event.secret)!!)
        assertNotNull(body)
        assertNull(body.nickname, "no nickname in what leaves the phone")
        assertNull(body.replyKey, "no reply key in it either")
        assertTrue(body.extras.anonymous)
        assertEquals("", mine.replyKey, "no reply key kept")
        assertEquals("", mine.replySecret)
        assertTrue(mine.anonymous)
        advanceTimeBy(30_000)

        val seen = d.staffIncidents.single()
        assertNull(seen.nickname)
        assertEquals("", seen.replyKey)
        assertTrue(seen.noReply)
        assertFalse(seen.witness)
    }

    @Test
    fun aReportWithoutTheOptionStillCanBeAnsweredAndKeepsItsNickname() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 810)
        val d = TestPhone("D", this, net, clock, tmp.root, event, 811)
        d.staffSecret = event.secret
        d.engine.setRole(staff = true, anchor = false)
        net.start()
        advanceTimeBy(30_000)

        a.incidents.report(draft("Ana", ReportExtras.NONE))
        advanceTimeBy(30_000)

        assertTrue(a.myReports.single().replyKey.isNotEmpty())
        val seen = d.staffIncidents.single()
        assertEquals("Ana", seen.nickname)
        assertTrue(seen.replyKey.isNotEmpty())
        assertFalse(seen.noReply)
        assertEquals(0, seen.happenedMinAgo)
    }

    @Test
    fun aReportWrittenLaterForSomeoneElseReachesStaffWithBoth() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 820)
        val d = TestPhone("D", this, net, clock, tmp.root, event, 821)
        d.staffSecret = event.secret
        d.engine.setRole(staff = true, anchor = false)
        net.start()
        advanceTimeBy(30_000)

        a.incidents.report(draft(null, ReportOptions.extras(laterMin = 180, witness = true)))
        advanceTimeBy(30_000)

        val seen = d.staffIncidents.single()
        assertEquals(180, seen.happenedMinAgo)
        assertTrue(seen.witness)
        assertFalse(seen.noReply)
        assertTrue(seen.replyKey.isNotEmpty(), "an answer can still come")
    }

    @Test
    fun theTimeOfAReportWrittenLaterStaysWithinTheLastDay() {
        val limit = ReportExtras.MAX_HAPPENED_MIN
        assertEquals(24 * 60, limit)
        assertNull(ReportOptions.later(0), "now is no time of its own")
        assertNull(ReportOptions.later(-5))
        assertEquals(1, ReportOptions.later(1))
        assertEquals(limit, ReportOptions.later(limit))
        assertNull(ReportOptions.later(limit + 1), "more than a day back")
        assertTrue(ReportOptions.laterChoices.all { it in 1..limit }, "every choice the screen offers can be sent")
        assertEquals(limit, ReportOptions.laterChoices.max(), "up to a whole day")

        val now = 1_760_000_000_000L
        assertEquals(90, ReportOptions.minutesSince(now - 90 * 60_000L - 5_000L, now))
        assertNull(ReportOptions.minutesSince(now + 60_000L, now), "not in the future")
        assertNull(ReportOptions.minutesSince(now - 25 * 3_600_000L, now), "not yesterday")
    }

    @Test
    fun theChosenOptionsBecomeTheContractsExtensionEntries() {
        assertEquals(ReportExtras.NONE, ReportOptions.extras())
        assertEquals(ReportExtras(happenedMinAgo = 60), ReportOptions.extras(laterMin = 60))
        assertEquals(ReportExtras.NONE, ReportOptions.extras(laterMin = ReportExtras.MAX_HAPPENED_MIN + 1), "out of bounds is left out")
        assertEquals(ReportExtras(anonymous = true, witness = true), ReportOptions.extras(anonymous = true, witness = true))
        // entries 2, 3 and 4 of the report body's extension block
        assertEquals("02020078" + "0300" + "0400", ReportOptions.extras(120, anonymous = true, witness = true).encode().toHex())
    }

    // --- the check-in alert between phones ---

    @Test
    fun aMissedCheckinReachesAFriendWithTheLastPositionAndIsConfirmed() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 830)
        val b = TestPhone("B", this, net, clock, tmp.root, event, 831)
        val stranger = TestPhone("C", this, net, clock, tmp.root, event, 832)
        a.befriend(b)
        net.start()
        advanceTimeBy(30_000)

        val fix = LastFix(44.4271, 26.1031, 1_760_000_000L, 2)
        assertEquals(1, a.chat.sendCheckinMissed(listOf(b.nodeId, stranger.nodeId), 1_760_000_600L, fix, "Gară"), "only the friend is told")
        advanceTimeBy(10_000)

        val (sender, alert, _) = b.checkinAlerts.single()
        assertEquals(a.nodeId, sender)
        assertEquals("Gară", alert.label)
        assertEquals(1_760_000_600L, alert.deadline)
        assertEquals(fix, alert.lastFix)
        assertTrue(a.chatStore.value.outbox.isEmpty(), "confirmed by B")
        assertTrue(stranger.checkinAlerts.isEmpty())
    }

    @Test
    fun withNobodyInRangeTheAlertWaitsInTheOutboxAndGoesWhenAFriendIsThere() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 840)
        val b = TestPhone("B", this, net, clock, tmp.root, event, 841)
        a.befriend(b)
        a.ignore(b)
        b.ignore(a)
        net.start()
        advanceTimeBy(30_000)

        a.chat.sendCheckinMissed(listOf(b.nodeId), 1_760_000_600L, null, "Cazare")
        advanceTimeBy(120_000)
        assertEquals(1, a.alertsInOutbox(), "queued")
        assertTrue(b.checkinAlerts.isEmpty())

        a.ignore()
        b.ignore()
        advanceTimeBy(120_000)
        val (_, alert, _) = b.checkinAlerts.single()
        assertNull(alert.lastFix)
        assertEquals("Cazare", alert.label)
        assertEquals(0, a.alertsInOutbox())
    }

    @Test
    fun theWholeWayFromSilenceToTheFriendsScreen() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 850)
        val b = TestPhone("B", this, net, clock, tmp.root, event, 851)
        a.befriend(b)
        net.start()
        advanceTimeBy(30_000)

        val notices = mutableListOf<SafetyNotice>()
        val mine = CheckinManager(
            backgroundScope, JsonStore(tmp.root / "a-checkin.json", CheckinData.serializer(), CheckinData(), backgroundScope), clock,
            friends = { a.friends.value }, lastFix = { GeoFix(44.4271, 26.1031, 6f, clock.wallMs() - 60_000L) },
            alert = a.chat::sendCheckinMissed, tell = { _, _ -> }, notify = { notices += it },
        ).also { it.start() }
        val theirs = CheckinManager(
            backgroundScope, JsonStore(tmp.root / "b-checkin.json", CheckinData.serializer(), CheckinData(), backgroundScope), clock,
            friends = { b.friends.value }, lastFix = { null }, alert = { _, _, _, _ -> 0 }, tell = { _, _ -> }, notify = { notices += it },
        )

        assertTrue(mine.begin("Gară", 20, listOf(b.nodeId)))
        advanceTimeBy(29 * 60_000L)
        assertTrue(a.chatStore.value.outbox.isEmpty(), "nothing queued while the person has time to answer")
        assertTrue(b.checkinAlerts.isEmpty())
        advanceTimeBy(2 * 60_000L)
        for ((sender, alert, hops) in b.checkinAlerts) theirs.onFriendMissed(sender, alert, hops)

        val shown = theirs.state.value.alerts.single()
        assertEquals(a.nodeId, shown.sender)
        assertEquals("Gară", shown.label)
        assertEquals(44.4271, shown.lat)
        assertTrue(notices.contains(SafetyNotice.FriendSilent(b.chat.nameOf(a.nodeId), "Gară")))
        assertEquals(1, b.checkinAlerts.size, "one alert, not one per retry")
    }

    @Test
    fun aFriendWeNeverAddedIsNotBelieved() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 860)
        val b = TestPhone("B", this, net, clock, tmp.root, event, 861)
        // A knows B's keys, B knows nothing of A
        a.friends.update { it + Friend(b.nodeId, "B", b.identity.box.publicKey.toHex(), "", 0) }
        net.start()
        advanceTimeBy(30_000)

        a.chat.sendCheckinMissed(listOf(b.nodeId), 1_760_000_600L, null, "Gară")
        advanceTimeBy(60_000)

        assertTrue(b.checkinAlerts.isEmpty())
    }
}
