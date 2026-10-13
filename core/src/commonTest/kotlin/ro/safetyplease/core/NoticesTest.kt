package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTemplate
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.mesh.CachedIncidents
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.IncidentCache
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.mesh.V10Node
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.HeldEntry
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.SummaryKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The console's notices on the simulated radio (PROTOCOL.md, sections 6.2 and 6.8): checked at every hop, the latest of
 * a topic replacing the older, withdrawn and expired ones shown nowhere, late phones caught up by store-and-forward,
 * and 1.0 phones never seeing one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticesTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private inner class World(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val net = SimNet(scope.backgroundScope)
        val event = TestEvent()
        private var seed = 900L

        fun phone(name: String) = TestPhone(name, scope, net, clock, tmp.root, event, seed++)

        fun console(name: String) = phone(name).also { it.activate(event.shift(CertRole.CONTROL, "Control")) }

        /** Each phone hears only the ones next to it in the list. */
        fun chain(vararg phones: TestPhone) {
            for (i in phones.indices) for (j in i + 2 until phones.size) net.outOfRange += setOf(phones[i].name, phones[j].name)
        }

        fun nowSec() = clock.wallMs() / 1000
    }

    private val pause = template(3, NoticeTopic.ALERT, NoticeLevel.PAUSE, params = 1)
    private val info = template(1, NoticeTopic.ALERT, NoticeLevel.INFO)
    private val heat = template(12, NoticeTopic.HEAT, 3)

    private fun template(id: Int, topic: Int, level: Int, params: Int = 0) = NoticeTemplate(
        id, topic, level, mapOf("ro" to "Text {1}", "en" to "Text {1}"), List(params) { mapOf("ro" to "Ora", "en" to "Time") },
    )

    private fun TestScope.started(w: World) {
        w.net.start()
        advanceTimeBy(30_000)
    }

    private fun TestPhone.active(w: World): List<Notice> = notices.board.value.active(w.nowSec()).map { it.second }

    private fun TestPhone.drops(reason: DropReason): Long = engine.state.value.counters.drops[reason] ?: 0

    @Test
    fun aNoticeCrossesFourHopsAndAForgeryStopsAtTheFirst() = runTest {
        val w = World(this)
        val console = w.console("K")
        val phones = listOf("A", "B", "C", "D").map { w.phone(it) }
        val forger = w.phone("F")
        w.chain(console, *phones.toTypedArray())
        // the forger hears only A
        for (p in listOf(console) + phones.drop(1)) w.net.outOfRange += setOf("F", p.name)
        started(w)

        assertTrue(console.notices.canSign)
        assertFalse(phones[0].notices.canSign, "a participant signs nothing")
        val sent = console.notices.publish(pause, listOf("15:30"), "", 3600)!!
        advanceTimeBy(15_000)
        assertEquals(sent.seq, phones[3].active(w).single().seq, "on the fourth phone of the chain within 15 s")
        assertEquals(sent.seq, console.active(w).single().seq)

        // a valid certificate under a signature that doesn't match: malformed at the first hop, relayed by nobody
        val forged = Notice.sign(testCrypto, console.keyChain.credential!!, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 9, w.nowSec(), w.nowSec() + 600, 5)
            .encode().also { it[3] = 1 }
        forger.engine.broadcast(PacketType.ANNOUNCEMENT, forged)
        // and one a team signed: not trusted, so it only crosses as a type nobody here reads
        val team = w.event.staffCrypto.credentialFrom(w.event.shift(CertRole.STAFF, "Medical 1"))!!.first
        val byATeam = Notice.sign(testCrypto, team, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 10, w.nowSec(), w.nowSec() + 600, 5)
        forger.engine.broadcast(PacketType.ANNOUNCEMENT, byATeam.encode())
        advanceTimeBy(15_000)
        assertEquals(1L, phones[0].drops(DropReason.UNTRUSTED), "the forgery stops at the first phone")
        for (p in phones.drop(1)) assertEquals(0L, p.drops(DropReason.UNTRUSTED))
        for (p in phones) {
            assertEquals(NoticeLevel.PAUSE, p.active(w).single().level)
            assertEquals(0L, p.drops(DropReason.MALFORMED))
        }
    }

    @Test
    fun theLatestOfATopicReplacesTheOlderAndAWithdrawnOrExpiredOneShowsNowhere() = runTest {
        val w = World(this)
        val console = w.console("K")
        val phone = w.phone("A")
        started(w)

        // 20 s apart: a link takes 4 announcements a minute
        val first = console.notices.publish(info, emptyList(), "", 3600)!!
        advanceTimeBy(20_000)
        val second = console.notices.publish(pause, listOf("16:00"), "main-stage", 3600)!!
        advanceTimeBy(20_000)
        assertEquals(listOf(second.seq), phone.active(w).map { it.seq })
        assertEquals(listOf("16:00"), phone.active(w).single().params)

        // an older notice sent again is stopped at the first phone that knows a later one
        console.engine.broadcast(PacketType.ANNOUNCEMENT, first.encode())
        advanceTimeBy(20_000)
        assertEquals(1L, phone.drops(DropReason.STALE_ACK))
        assertEquals(second.seq, phone.active(w).single().seq)

        val cancel = console.notices.cancel(NoticeTopic.ALERT)!!
        advanceTimeBy(20_000)
        assertTrue(cancel.cancelled)
        assertTrue(phone.active(w).isEmpty())
        assertEquals(cancel.seq, phone.notices.board.value.notices.single().seq)
        assertNull(console.notices.cancel(NoticeTopic.ALERT), "nothing left to withdraw")

        val hot = console.notices.publish(heat, emptyList(), "", 600)!!
        advanceTimeBy(5_000)
        assertEquals(NoticeTopic.HEAT, phone.active(w).single().topic)
        advanceTimeBy(600_000)
        assertTrue(phone.active(w).isEmpty())
        console.engine.broadcast(PacketType.ANNOUNCEMENT, hot.encode())
        advanceTimeBy(5_000)
        assertEquals(2L, phone.drops(DropReason.STALE_ACK), "an expired notice goes nowhere")
    }

    @Test
    fun aPhoneThatComesLaterGetsTheNoticesInForceFromItsNeighbour() = runTest {
        val w = World(this)
        val console = w.console("K")
        val relay = w.phone("R")
        val late = w.phone("L")
        w.net.outOfRange += setOf(setOf("K", "L"), setOf("R", "L"))
        started(w)

        console.notices.publish(heat, emptyList(), "", 4 * 3600)
        console.notices.publish(pause, listOf("15:30"), "", 3600)
        advanceTimeBy(10_000)
        assertEquals(2, relay.active(w).size)

        // the console goes; five minutes later the late phone meets the relay
        w.net.cut("K", "R")
        advanceTimeBy(5 * 60_000L)
        w.net.heal("R", "L")
        advanceTimeBy(70_000)
        assertEquals(setOf(NoticeTopic.ALERT, NoticeTopic.HEAT), late.active(w).map { it.topic }.toSet())
        assertTrue(relay.engine.state.value.counters.storeAndForward >= 2)
    }

    @Test
    fun aNoticeIssuedJustBeforeTheShiftEndsStillReachesAPhoneThatComesAfterIt() = runTest {
        val w = World(this)
        val console = w.phone("K").also { it.activate(w.event.shift(CertRole.CONTROL, "Control", until = 30 * 60L)) }
        val relay = w.phone("R")
        val late = w.phone("L")
        w.net.outOfRange += setOf(setOf("K", "L"), setOf("R", "L"))
        started(w)

        advanceTimeBy(25 * 60_000L)
        console.notices.publish(heat, emptyList(), "", 4 * 3600)!!
        advanceTimeBy(10_000)
        assertEquals(listOf(NoticeTopic.HEAT), relay.active(w).map { it.topic })

        // the shift and the clock skew are over when the late phone meets the relay
        w.net.cut("K", "R")
        advanceTimeBy(20 * 60_000L)
        assertFalse(console.notices.canSign)
        w.net.heal("R", "L")
        advanceTimeBy(70_000)
        assertEquals(listOf(NoticeTopic.HEAT), late.active(w).map { it.topic })

        // the same key can't sign one now that its shift is over
        val after = Notice.sign(testCrypto, console.keyChain.credential!!, NoticeTopic.HEAT, 4, 9, w.nowSec(), w.nowSec() + 600, 12)
        assertEquals(Verdict.REFUSED, late.keyChain.checkAnnouncement(after.encode()))
    }

    @Test
    fun theConsoleSendsThemAgainAndOnlyPhonesWithoutThemPay() = runTest {
        val w = World(this)
        val console = w.console("K").also { it.resendsNotices = true }
        val a = w.phone("A")
        val b = w.phone("B")
        w.chain(console, a, b)
        started(w)

        console.notices.publish(pause, listOf("15:30"), "", 3600)
        console.notices.publish(heat, emptyList(), "", 4 * 3600)
        advanceTimeBy(10_000)
        val before = w.net.traffic(PacketType.ANNOUNCEMENT).transmissions
        assertEquals(4, before)
        advanceTimeBy(10 * 60_000L)
        assertEquals(before + 1, w.net.traffic(PacketType.ANNOUNCEMENT).transmissions, "the alert, once to A, which holds it and stops it")
        advanceTimeBy(20 * 60_000L)
        assertEquals(before + 4, w.net.traffic(PacketType.ANNOUNCEMENT).transmissions, "the alert twice more, the heat once")
        assertEquals(4L, a.drops(DropReason.STALE_ACK))
        assertEquals(0L, b.drops(DropReason.STALE_ACK))
    }

    @Test
    fun oneZeroPhonesNeverSeeANotice() = runTest {
        val w = World(this)
        val console = w.console("K")
        val phone = w.phone("A")
        val old = V10Node(backgroundScope, w.net, "V", 1L, w.clock, { w.event.staffCrypto.verifyAck(it) })
        started(w)

        console.notices.publish(pause, listOf("15:30"), "", 3600)
        advanceTimeBy(3 * 60_000L)
        assertEquals(1, phone.active(w).size)
        assertTrue(old.received.none { it.type == PacketType.ANNOUNCEMENT })
        assertEquals(0, old.malformed)
        for (p in listOf(console, phone)) assertEquals(0L, p.drops(DropReason.MALFORMED))
    }

    @Test
    fun theCacheKeepsANoticeUntilItExpiresAcrossARestart() {
        val now = 1_000_000L
        val wall = 1_760_000_000_000L
        val event = TestEvent()
        val credential = event.staffCrypto.credentialFrom(event.shift(CertRole.CONTROL, "Control"))!!.first
        val notice = Notice.sign(testCrypto, credential, NoticeTopic.HEAT, 2, 4, wall / 1000, wall / 1000 + 7200, 11)
        val packet = Packet(PacketType.ANNOUNCEMENT, 6, 42, 7, wall / 1000, null, false, notice.encode())
        val cache = IncidentCache()
        assertTrue(cache.offerNotice(notice, packet, now, wall))
        assertFalse(cache.offerNotice(notice, packet, now, wall), "the same sequence number again")
        assertEquals(listOf(HeldEntry(NoticeTopic.HEAT.toLong(), 4)), cache.held(SummaryKind.NOTICES, now))

        // saved an hour later, taken back after a reboot at 1 h 59 min: past the hour incidents are kept, not the notice's two
        val saved: CachedIncidents = cache.snapshot(now + 3_600_000, wall + 3_600_000)
        val back = IncidentCache()
        back.restore(saved, 5_000, wall + 2 * 3_600_000L - 60_000, 60 * 60_000L)
        assertEquals(NoticeTopic.HEAT.toLong(), back.held(SummaryKind.NOTICES, 5_000).single().id8)
        assertEquals(listOf(NoticeTopic.HEAT.toLong()), back.missing(SummaryKind.NOTICES, listOf(HeldEntry(2, 5)), 5_000))
        assertTrue(back.missing(SummaryKind.NOTICES, listOf(HeldEntry(2, 4)), 5_000).isEmpty())

        val expired = IncidentCache()
        expired.restore(saved, 5_000, wall + 2 * 3_600_000L + 1, 60 * 60_000L)
        assertTrue(expired.held(SummaryKind.NOTICES, 5_000).isEmpty())
        assertFalse(expired.offerNotice(notice, packet, 5_000, wall), "its sequence number stays")
    }
}
