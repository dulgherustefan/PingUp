package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.announce.Evacuation
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.HeldNotice
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeOrder
import ro.safetyplease.core.announce.NoticeReach
import ro.safetyplease.core.announce.NoticeTemplate
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.mesh.CachedIncidents
import ro.safetyplease.core.mesh.CachedNotice
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.IncidentCache
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.SummaryKind
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the console's notices must do whoever else signs, whatever the clocks say and whichever version a phone runs
 * (PROTOCOL.md, section 6.8): a replacement console is heard, no number blocks a topic, an evacuation stays until it is
 * withdrawn, a phone with a wrong clock still evacuates, and phones of earlier versions keep the evacuation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoticeSafetyTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private fun square(lat: Double) = listOf(GeoPoint(lat, 26.0), GeoPoint(lat, 26.001), GeoPoint(lat + 0.001, 26.001), GeoPoint(lat + 0.001, 26.0))

    private val venue = Venue(
        "T", Bounds(44.0, 26.0, 44.01, 26.01),
        listOf(Zone("A", "Zona A", "#fff", square(44.0)), Zone("B", "Zona B", "#fff", square(44.002)), Zone("C", "Zona C", "#fff", square(44.004))),
    )

    private fun template(id: Int, topic: Int, level: Int, params: Int = 0) =
        NoticeTemplate(id, topic, level, mapOf("ro" to "T {1}", "en" to "T {1}"), List(params) { mapOf("ro" to "P", "en" to "P") })

    private val info = template(1, NoticeTopic.ALERT, NoticeLevel.INFO)
    private val pause = template(3, NoticeTopic.ALERT, NoticeLevel.PAUSE, params = 1)
    private val evac = template(8, NoticeTopic.ALERT, NoticeLevel.EVACUATION)
    private val heat = template(12, NoticeTopic.HEAT, 3)

    private inner class World(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val net = SimNet(scope.backgroundScope)
        val event = TestEvent()
        private var seed = 1300L

        fun phone(name: String, clock: TestClock = this.clock) = TestPhone(name, scope, net, clock, tmp.root, event, seed++)

        fun console(name: String, team: String = "Control") = phone(name).also { it.activate(event.shift(CertRole.CONTROL, team)) }

        fun nowSec() = clock.wallMs() / 1000
    }

    private fun TestScope.started(w: World) {
        w.net.start()
        advanceTimeBy(30_000)
    }

    /** On the phone's own clock, as its screens read the board. */
    private fun TestPhone.nowSec(): Long = clock.wallMs() / 1000

    private fun TestPhone.active(): List<Pair<HeldNotice, Notice>> = notices.board.value.active(nowSec())

    private fun TestPhone.state(): MapState = MapState.of(notices.board.value, venue, nowSec())

    private fun TestPhone.evacuations(): List<Notice> = active().map { it.second }.filter(NoticeReach::isEvacuation)

    private fun TestPhone.alerts(): List<Notice> = active().map { it.second }.filter { it.topic == NoticeTopic.ALERT && it.level != NoticeLevel.EVACUATION }

    private suspend fun TestPhone.evacuate(vararg zones: String): Notice =
        notices.publish(evac, emptyList(), "", 3600, Evacuations.extras(venue, null, zones.toSet())!!)!!

    @Test
    fun aReplacementConsoleIsHeardAfterTheOldOnesNoticesExpired() = runTest {
        val w = World(this)
        val k1 = w.console("K1")
        val p = w.phone("P")
        val k2 = w.console("K2", "Control 2")
        k2.radio.powerOff()
        started(w)
        repeat(3) {
            k1.notices.publish(info, emptyList(), "", 120)
            advanceTimeBy(20_000)
        }
        advanceTimeBy(5 * 60_000L)
        // the first console breaks; the spare, which never heard it, takes over
        k1.radio.powerOff()
        k2.radio.powerOn()
        advanceTimeBy(60_000)
        val sent = assertNotNull(k2.notices.publish(evac, emptyList(), "", 3600))
        advanceTimeBy(30_000)
        assertEquals(listOf(sent.seq), p.evacuations().map { it.seq }, "the participant follows the spare console's evacuation")
        assertEquals(listOf(sent.seq), k2.evacuations().map { it.seq })
    }

    @Test
    fun evacuationsAddUpAndNoOtherAlertEndsThem() = runTest {
        val w = World(this)
        val k = w.console("K")
        val p = w.phone("P")
        started(w)
        k.evacuate("A")
        advanceTimeBy(20_000)
        assertEquals(Evacuation(null, setOf("A")), p.state().evacuation)
        val second = k.evacuate("B")
        advanceTimeBy(20_000)
        assertEquals(Evacuation(null, setOf("A", "B")), p.state().evacuation, "zone A stays evacuated")
        assertEquals(setOf("A", "B"), second.extras.zones!!.ids(venue), "the console sends both, for phones that keep one")
        val told = assertNotNull(k.notices.publish(info, emptyList(), "", 3600))
        advanceTimeBy(20_000)
        k.notices.publish(pause, listOf("16:00"), "", 3600)
        advanceTimeBy(20_000)
        assertEquals(Evacuation(null, setOf("A", "B")), p.state().evacuation, "an information or a pause ends no evacuation")
        assertEquals(listOf(NoticeLevel.PAUSE), p.alerts().map { it.level }, "the pause replaced the information")
        assertTrue(told.seq < second.seq, "numbered below the evacuation, so an earlier version keeps the evacuation")
        assertEquals(k.state(), p.state(), "the console reads the map as the phones do")

        val withdrawal = assertNotNull(k.notices.withdraw(second))
        advanceTimeBy(20_000)
        assertTrue(withdrawal.cancelled)
        assertNull(p.state().evacuation, "the withdrawal ends every evacuation")
        assertEquals(listOf(NoticeLevel.PAUSE), p.alerts().map { it.level }, "and leaves the pause")
        assertTrue(p.alerts().single().seq > withdrawal.seq, "signed again above the withdrawal, for the earlier versions")
    }

    @Test
    fun aStolenKeysNumbersHoldNoTopicAndItsRevocationClearsWhatItSigned() = runTest {
        val w = World(this)
        val k = w.console("K")
        val thief = w.console("T", "Control 2")
        val p = w.phone("P")
        started(w)
        val stolen = thief.keyChain.credential!!
        val highest = Notice.sign(testCrypto, stolen, NoticeTopic.ALERT, NoticeLevel.PAUSE, 0xFFFFFFFFL, w.nowSec(), w.nowSec() + 3600, 3, params = listOf("x"))
        thief.engine.broadcast(PacketType.ANNOUNCEMENT, highest.encode())
        advanceTimeBy(20_000)
        assertEquals(listOf(highest.seq), p.alerts().map { it.seq })

        val real = assertNotNull(k.notices.publish(info, emptyList(), "", 3600), "the console still signs")
        advanceTimeBy(20_000)
        assertEquals(listOf(real.seq), p.alerts().map { it.seq }, "a later issue time wins over the highest number")

        // dated six hours ahead: taken, as of uncertain time, but it orders no later than the clock plus the skew
        val ahead = Notice.sign(
            testCrypto, stolen, NoticeTopic.ALERT, NoticeLevel.PAUSE, 7, w.nowSec() + 6 * 3600, w.nowSec() + 7 * 3600, 3, params = listOf("y"),
        )
        thief.engine.broadcast(PacketType.ANNOUNCEMENT, ahead.encode())
        advanceTimeBy(20_000)
        assertEquals(listOf(ahead.seq), p.alerts().map { it.seq })
        assertTrue(p.active().single { it.second.seq == ahead.seq }.first.uncertain)
        val next = assertNotNull(k.notices.publish(info, emptyList(), "", 3600), "the console isn't held back")
        advanceTimeBy(20_000)
        assertEquals(listOf(next.seq), p.alerts().map { it.seq })

        val fake = Notice.sign(testCrypto, stolen, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 9, w.nowSec(), w.nowSec() + 3600, 8)
        thief.engine.broadcast(PacketType.ANNOUNCEMENT, fake.encode())
        advanceTimeBy(20_000)
        assertEquals(listOf(fake.seq), p.evacuations().map { it.seq })
        k.revocations.add(w.event.revocation(stolen.certificate))
        advanceTimeBy(20_000)
        assertTrue(p.evacuations().isEmpty(), "the revocation takes the stolen key's evacuation away")
        assertTrue(p.notices.board.value.notices.none { it.notice()!!.certificate.id == stolen.certificate.id })
        assertEquals(listOf(next.seq), p.alerts().map { it.seq })
    }

    @Test
    fun aPhoneWhoseClockIsBehindGetsTheEvacuationMarkedAsOfUncertainTime() = runTest {
        val w = World(this)
        val k = w.console("K")
        val behind = w.phone("P", TestClock(testScheduler, offsetMs = -15 * 60_000L))
        started(w)
        val evacuation = k.evacuate("A")
        advanceTimeBy(20_000)
        assertEquals(Verdict.VALID, behind.keyChain.checkAnnouncement(evacuation.encode()), "issued 15 minutes past its clock")
        val (held, shown) = behind.active().single()
        assertEquals(evacuation.seq, shown.seq)
        assertTrue(held.uncertain)
        assertFalse(k.active().single().first.uncertain)
        // it ends after its own length from when it came, not 15 minutes late
        advanceTimeBy(3600_000L - 2 * 60_000L)
        assertEquals(1, behind.evacuations().size)
        advanceTimeBy(2 * 60_000L)
        assertTrue(behind.evacuations().isEmpty())
    }

    @Test
    fun aPhoneAheadOfItsNeighboursReadsNoticesOnTheirClockAndPassesTheEvacuationOn() = runTest {
        val w = World(this)
        val k = w.console("K")
        val n1 = w.phone("N1")
        val n2 = w.phone("N2")
        val ahead = w.phone("A", TestClock(testScheduler, offsetMs = 2 * 3600_000L))
        // the last phone hears only the one whose clock is wrong
        val last = w.phone("Q")
        for (other in listOf(k, n1, n2)) w.net.outOfRange += setOf(last.name, other.name)
        started(w)
        k.notices.publish(heat, emptyList(), "", 4 * 3600)
        // its neighbours hold a notice, so they list it to it every minute with their clock on the summary
        advanceTimeBy(3 * 60_000L)
        // header times are whole seconds
        assertTrue(ahead.notices.clockOffsetS.value in -2 * 3600L - 1..-2 * 3600L + 1)
        assertEquals(0L, n1.notices.clockOffsetS.value)

        val evacuation = k.notices.publish(evac, emptyList(), "", 30 * 60L)!!
        advanceTimeBy(20_000)
        val held = ahead.active().single { it.second.seq == evacuation.seq }.first
        assertTrue(held.uncertain, "shown as of uncertain time on the phone whose clock is wrong")
        assertEquals(listOf(evacuation.seq), last.evacuations().map { it.seq }, "and passed on")
        assertFalse(last.active().single { it.second.seq == evacuation.seq }.first.uncertain)

        // a phone with a right clock still drops it once it ended
        advanceTimeBy(31 * 60_000L)
        val before = n2.engine.state.value.counters.drops[DropReason.STALE_ACK] ?: 0
        n1.engine.broadcast(PacketType.ANNOUNCEMENT, evacuation.encode())
        advanceTimeBy(20_000)
        assertTrue(n2.evacuations().isEmpty())
        assertEquals(before + 1, n2.engine.state.value.counters.drops[DropReason.STALE_ACK])
    }

    @Test
    fun aConsoleWhoseClockIsBehindDatesItsNoticesOnItsNeighboursClock() = runTest {
        val w = World(this)
        val first = w.console("K0")
        val phones = listOf("A", "B", "C").map { w.phone(it) }
        val behind = w.phone("K", TestClock(testScheduler, offsetMs = -30 * 60_000L))
        behind.activate(w.event.shift(CertRole.CONTROL, "Control 2", from = -3600))
        started(w)
        first.notices.publish(heat, emptyList(), "", 4 * 3600)
        advanceTimeBy(3 * 60_000L)
        assertTrue(behind.notices.clockOffsetS.value in 30 * 60L - 1..30 * 60L + 1)
        // on its own clock this evacuation would have ended ten minutes before it was sent
        val evacuation = assertNotNull(behind.notices.publish(evac, emptyList(), "", 20 * 60L))
        assertTrue(evacuation.issued >= w.nowSec() - 1)
        advanceTimeBy(20_000)
        for (p in phones) assertEquals(listOf(evacuation.seq), p.evacuations().map { it.seq }, p.name)
    }

    /** A phone of an earlier version: one notice per topic, the highest number it ever took, until it expires. */
    private class EarlierPhone {
        val marks = HashMap<Int, Long>()
        val held = HashMap<Int, Notice>()

        fun offer(notice: Notice, nowSec: Long): Boolean {
            if (notice.validUntil <= nowSec || notice.seq <= (marks[notice.topic] ?: 0)) return false
            marks[notice.topic] = notice.seq
            held[notice.topic] = notice
            return true
        }

        fun shows(topic: Int, nowSec: Long): Notice? = held[topic]?.takeIf { !it.cancelled && it.validUntil > nowSec }
    }

    @Test
    fun phonesOfEarlierVersionsTakeTheConsolesNoticesAndKeepTheEvacuation() = runTest {
        val w = World(this)
        val k = w.console("K")
        started(w)
        val old = EarlierPhone()
        // the console before this one numbered its notices from 1
        val before = Notice.sign(testCrypto, k.keyChain.credential!!, NoticeTopic.ALERT, NoticeLevel.INFO, 3, w.nowSec() - 600, w.nowSec() - 60, 1)
        assertTrue(old.offer(before, w.nowSec() - 300))
        fun signed(): List<Notice> = k.notices.board.value.notices.mapNotNull { it.notice() }

        val first = k.evacuate("A")
        assertTrue(old.offer(first, w.nowSec()), "a new console's first notice is numbered above an older one's")
        advanceTimeBy(5_000)
        val told = k.notices.publish(info, emptyList(), "", 3600)!!
        assertFalse(old.offer(told, w.nowSec()), "numbered below the evacuation")
        assertEquals(first.seq, old.shows(NoticeTopic.ALERT, w.nowSec())?.seq, "so the earlier version keeps the evacuation")
        advanceTimeBy(5_000)
        val second = k.evacuate("B")
        assertTrue(old.offer(second, w.nowSec()))
        assertEquals(setOf("A", "B"), old.shows(NoticeTopic.ALERT, w.nowSec())!!.extras.zones!!.ids(venue))
        advanceTimeBy(5_000)
        val withdrawal = k.notices.withdraw(second)!!
        assertTrue(old.offer(withdrawal, w.nowSec()))
        val again = signed().single { !it.cancelled && it.level == NoticeLevel.INFO && it.seq > withdrawal.seq }
        assertTrue(old.offer(again, w.nowSec()))
        assertEquals(NoticeLevel.INFO, old.shows(NoticeTopic.ALERT, w.nowSec())?.level, "the information comes back after the withdrawal")
    }

    @Test
    fun aLatePhoneGetsTheEvacuationAndTheOtherAlertFromItsNeighbour() = runTest {
        val w = World(this)
        val k = w.console("K")
        val relay = w.phone("R")
        val late = w.phone("L")
        w.net.outOfRange += setOf(setOf("K", "L"), setOf("R", "L"))
        started(w)
        val evacuation = k.evacuate("A")
        advanceTimeBy(20_000)
        val told = k.notices.publish(info, emptyList(), "", 3600)!!
        advanceTimeBy(20_000)
        w.net.cut("K", "R")
        advanceTimeBy(5 * 60_000L)
        w.net.heal("R", "L")
        advanceTimeBy(70_000)
        assertEquals(setOf(evacuation.seq, told.seq), late.active().map { it.second.seq }.toSet())
        assertEquals(Evacuation(null, setOf("A")), late.state().evacuation)
    }

    @Test
    fun aNewEvacuationTakesTheScreenOnlyWhereTheOneItTakesInDidNot() = runTest {
        val w = World(this)
        val k = w.console("K")
        val followed = w.phone("P")
        val other = w.phone("Q")
        started(w)
        val first = k.evacuate("A")
        advanceTimeBy(20_000)
        followed.notices.alerted(first.topic, first.seq)
        val second = k.evacuate("B")
        advanceTimeBy(20_000)
        assertTrue(followed.active().single().first.alerted, "the evacuation mode doesn't come back for zone B added")
        assertFalse(other.active().single().first.alerted)
        assertEquals(listOf(second.seq), followed.evacuations().map { it.seq })
    }

    @Test
    fun theConsoleSeesHowManyPhonesAroundHoldItsNotice() = runTest {
        val w = World(this)
        val k = w.console("K")
        w.phone("A")
        w.phone("B")
        started(w)
        val sent = k.notices.publish(heat, emptyList(), "", 3600)!!
        assertEquals(null, k.notices.heldAround.value[NoticeOrder.extraId(sent)]?.takeIf { it > 0 })
        advanceTimeBy(2 * 60_000L)
        assertEquals(2, k.notices.heldAround.value[NoticeOrder.extraId(sent)])
    }

    @Test
    fun theCacheLetsGoOfANumberAnEarlierVersionKeptForGoodAndListsEverySlot() {
        val now = 1_000_000L
        val wall = 1_760_000_000_000L
        val event = TestEvent()
        val credential = event.staffCrypto.credentialFrom(event.shift(CertRole.CONTROL, "Control"))!!.first
        val cache = IncidentCache()
        cache.restore(CachedIncidents(notices = listOf(CachedNotice(NoticeTopic.ALERT.toLong(), null, 0xFFFFFFFFL))), now, wall, 3600_000L)
        fun packet(n: Notice) = Packet(PacketType.ANNOUNCEMENT, 6, n.seq, 7, wall / 1000, null, false, n.encode())
        val evacuation = Notice.sign(testCrypto, credential, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 50, wall / 1000, wall / 1000 + 3600, 8)
        val alert = Notice.sign(testCrypto, credential, NoticeTopic.ALERT, NoticeLevel.INFO, 2, wall / 1000 + 1, wall / 1000 + 3600, 1)
        assertTrue(cache.offerNotice(evacuation, packet(evacuation), now, wall), "the old number blocks nothing any more")
        assertTrue(cache.offerNotice(alert, packet(alert), now, wall), "a slot of its own: the evacuation stays")

        val held = cache.held(SummaryKind.NOTICES, now)
        assertEquals(NoticeTopic.ALERT.toLong() to 50L, held.first().id8 to held.first().version, "the topic's highest, as earlier versions read it")
        assertEquals(listOf(NoticeOrder.extraId(alert) to 0L), held.drop(1).map { it.id8 to it.version })
        // an earlier version asks only for a version above its own, so never for an entry of version 0
        assertTrue(held.filter { it.version > 0 }.all { it.id8 == NoticeTopic.ALERT.toLong() })
        val other = IncidentCache()
        assertEquals(held.map { it.id8 }, other.missing(SummaryKind.NOTICES, held, now))
        assertEquals(alert.encode().toList(), cache.kept(SummaryKind.NOTICES, NoticeOrder.extraId(alert))!!.payload.toList())
        assertEquals(evacuation.encode().toList(), cache.kept(SummaryKind.NOTICES, NoticeTopic.ALERT.toLong())!!.payload.toList())

        // a slot's order is let go a day after it was issued: whatever it could refuse has ended by then
        val later = wall + (Notice.MAX_VALID_S + 60) * 1000
        val saved = cache.snapshot(now, wall)
        val back = IncidentCache()
        back.restore(saved, now, later, 3600_000L)
        assertTrue(back.held(SummaryKind.NOTICES, now).isEmpty())
        assertEquals(0L, back.noticeSeq(NoticeTopic.ALERT))
        assertEquals(alert.issued - 1, NoticeOrder.orderTime(alert, alert.issued - KeyChain.SKEW_S - 1), "past the clock and the skew it counts as that much")
    }
}
