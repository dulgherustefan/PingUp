package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.MsgStatus
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.LinkLimits
import ro.safetyplease.core.mesh.Position
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Severity
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Crowds of 50 and 200 phones: reports reach staff, chat reaches friends, and each run prints how many
 * transmissions a unique packet costs across the crowd and what was dropped, so traffic changes can be compared.
 * The simulated radio has no shared airtime and no loss: these are counts, not latency or capacity.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScaleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Crowd(scope: TestScope, dir: File, size: Int) {
        val net = SimNet(scope.backgroundScope)
        private val clock = TestClock(scope.testScheduler)
        private val event = TestEvent()

        // fixed identities and seeds: the same links form on every run, so runs before and after a change compare
        val phones = List(size) { i ->
            val seed = Random(i).nextBytes(32)
            val identity = Identity(testCrypto.boxKeyPairFromSeed(seed), testCrypto.signKeyPairFromSeed(seed))
            TestPhone("P$i", scope, net, clock, dir, event, seed = 2L * i, identity = identity)
        }

        val staff = mutableListOf<TestPhone>()

        fun makeStaff(phone: TestPhone) {
            phone.staffSecret = event.secret
            phone.engine.setRole(staff = true, anchor = false)
            staff += phone
        }

        fun distance(phone: TestPhone, to: Position): Double = phone.radio.position!!.let { hypot(it.x - to.x, it.y - to.y) }

        fun nearest(to: Position, among: List<TestPhone>): TestPhone = among.minBy { distance(it, to) }
    }

    private val urgent = ReportDraft(IncidentCategory.MEDICAL, Severity.URGENT, "", null, null, "", null)

    private class Outcome(
        val phones: Int,
        val links: Double,
        val coverage: Double,
        val reports: SimNet.Traffic,
        val acks: SimNet.Traffic,
        val chat: SimNet.Traffic,
        val messages: Int,
        val dropped: Long,
        val queueFull: Long,
        val unreachable: SimNet.Traffic,
        val unreachableMs: Long,
    ) {
        override fun toString(): String {
            fun line(name: String, t: SimNet.Traffic) =
                "$name: ${t.packets} packets, ${t.transmissions} transmissions, ${"%.1f".format(t.perPacket)} per packet"
            return listOf(
                "$phones phones, ${"%.1f".format(links)} links each, reports held by ${"%.0f".format(coverage * 100)}% of phones",
                line("INCIDENT_REPORT", reports),
                line("INCIDENT_ACK", acks),
                line("PRIVATE", chat) + ", ${"%.1f".format(chat.transmissions.toDouble() / messages)} per chat message with receipt",
                "dropped $dropped, queue full $queueFull",
                line("one message to a phone that is off, over ${unreachableMs / 1000} s", unreachable),
            ).joinToString("\n  ")
        }
    }

    /**
     * Lets links form, then every reporter files a report and both sides of every chat pair write at once.
     * Asserts that reports reach staff and every message arrives once, and measures that traffic. Then one more
     * message goes to a friend whose phone is off, and the floods its retries cost are measured on their own.
     */
    private fun TestScope.exercise(
        crowd: Crowd,
        reporters: List<TestPhone>,
        chats: List<Pair<TestPhone, TestPhone>>,
        unreachable: Pair<TestPhone, TestPhone>,
        settleMs: Long,
    ): Outcome {
        for ((a, b) in chats + unreachable) a.befriend(b)
        crowd.net.start()
        advanceTimeBy(60_000)

        for (r in reporters) assertTrue(r.incidents.report(urgent))
        for ((a, b) in chats) {
            a.chat.sendText(Conversations.friend(b.nodeId), "unde esti?")
            b.chat.sendText(Conversations.friend(a.nodeId), "la scena")
        }
        advanceTimeBy(settleMs)

        for (r in reporters) assertTrue(r.name, r.myReports.single().status >= AckStatus.RECEIVED)
        for ((a, b) in chats) for (phone in listOf(a, b)) {
            assertEquals(phone.name, MsgStatus.DELIVERED, phone.messages.single { it.fromMe }.status)
            assertEquals(phone.name, 1, phone.messages.count { !it.fromMe })
        }
        val phones = crowd.phones
        val states = phones.map { it.engine.state.value }
        val reports = crowd.net.traffic(PacketType.INCIDENT_REPORT)
        val acks = crowd.net.traffic(PacketType.INCIDENT_ACK)
        val chat = crowd.net.traffic(PacketType.PRIVATE)

        val (sender, offline) = unreachable
        offline.radio.powerOff()
        sender.chat.sendText(Conversations.friend(offline.nodeId), "mai ai baterie?")
        advanceTimeBy(settleMs)
        assertEquals(MsgStatus.SENT, sender.messages.single { it.fromMe }.status)
        val retries = crowd.net.traffic(PacketType.PRIVATE)

        val outcome = Outcome(
            phones = phones.size,
            links = states.map { it.readyLinks }.average(),
            coverage = states.sumOf { it.cachedReports }.toDouble() / (phones.size * reporters.size),
            reports = reports,
            acks = acks,
            chat = chat,
            messages = chats.size * 2,
            dropped = states.sumOf { it.dropped },
            queueFull = states.sumOf { it.queueFull },
            unreachable = SimNet.Traffic(retries.packets - chat.packets, retries.transmissions - chat.transmissions),
            unreachableMs = settleMs,
        )
        // a flood crosses each link at most once each way, plus store-and-forward copies on request
        val ceiling = 2.0 * phones.size * LinkLimits.DEFAULT.total
        assertTrue("$outcome", outcome.reports.perPacket <= ceiling && outcome.chat.perPacket <= ceiling)
        return outcome
    }

    @Test
    fun fiftyPhonesOnAGrid() = runTest {
        val crowd = Crowd(this, tmp.root, 50)
        val p = crowd.phones
        // 10 x 5, diagonal neighbors in range too
        crowd.net.range = 15.0
        crowd.net.grid(p.map { it.radio }, columns = 10, spacing = 10.0)
        crowd.makeStaff(p[22])
        crowd.makeStaff(p[27])
        val outcome = exercise(
            crowd,
            reporters = listOf(p[0], p[9], p[45]),
            chats = listOf(p[2] to p[6], p[10] to p[14], p[15] to p[19], p[31] to p[35], p[43] to p[47]),
            unreachable = p[40] to p[49],
            settleMs = 120_000,
        )
        println("Grid:\n  $outcome")
        assertEquals("every phone holds every report", 1.0, outcome.coverage, 0.0)
    }

    /** 200 phones over 100 x 100 m, heard 15 m away, with staff near the middle of each quarter. */
    private fun TestScope.festival(): Crowd {
        val crowd = Crowd(this, tmp.root, 200)
        crowd.net.range = 15.0
        crowd.net.scatter(crowd.phones.map { it.radio }, width = 100.0, height = 100.0, random = Random(7))
        assertTrue("the crowd starts as one network", crowd.net.connected())
        for (quarter in listOf(Position(25.0, 25.0), Position(75.0, 25.0), Position(25.0, 75.0), Position(75.0, 75.0))) {
            crowd.makeStaff(crowd.nearest(quarter, crowd.phones))
        }
        return crowd
    }

    @Test
    fun twoHundredPhonesScatteredAndWalking() = runTest {
        val crowd = festival()
        crowd.net.walk(Random(8), step = 1.0)
        val others = crowd.phones - crowd.staff.toSet()
        val corners = listOf(Position(0.0, 0.0), Position(100.0, 0.0), Position(0.0, 100.0), Position(100.0, 100.0))
        val reporters = (corners + Position(50.0, 50.0)).map { crowd.nearest(it, others) }
        // ten pairs of friends, each about three radio ranges apart
        val partners = others.drop(11).toMutableList()
        val chats = others.take(10).map { a ->
            val b = partners.minBy { abs(crowd.distance(it, a.radio.position!!) - 40.0) }
            partners -= b
            a to b
        }
        val outcome = exercise(crowd, reporters, chats, unreachable = others[10] to partners.first(), settleMs = 180_000)
        println("Random, walking:\n  $outcome")
        assertEquals("every phone holds every report", 1.0, outcome.coverage, 0.0)
    }

    @Test
    fun reportsOvertakeAChatBurst() = runTest {
        val crowd = festival()
        val others = crowd.phones - crowd.staff.toSet()
        val reporters = others.take(5)
        // everyone else writes, all at once, to the nearest phone in radio range that has no partner yet
        val free = others.drop(5).toMutableList()
        val pairs = buildList {
            while (free.size >= 2) {
                val a = free.removeAt(0)
                val b = free.minBy { crowd.distance(it, a.radio.position!!) }
                if (crowd.distance(b, a.radio.position!!) > crowd.net.range) continue
                free -= b
                add(a to b)
            }
        }
        for ((a, b) in pairs) a.befriend(b)
        crowd.net.start()
        advanceTimeBy(60_000)

        for ((a, b) in pairs) a.chat.sendText(Conversations.friend(b.nodeId), "ne vedem la intrare")
        advanceTimeBy(2_000)
        for (r in reporters) assertTrue(r.incidents.report(urgent))
        val start = testScheduler.currentTime
        // queued behind the burst at 2.8 packets/s per link, a report would need tens of seconds per hop
        while (reporters.any { it.myReports.single().status < AckStatus.RECEIVED } && testScheduler.currentTime - start < 10_000) {
            advanceTimeBy(250)
        }
        val confirmedMs = testScheduler.currentTime - start
        val stillOpen = reporters.filter { it.myReports.single().status < AckStatus.RECEIVED }.map { it.name }
        assertTrue("not confirmed within 10 s: $stillOpen", stillOpen.isEmpty())
        advanceTimeBy(60_000 - confirmedMs)

        val states = crowd.phones.map { it.engine.state.value }
        val chat = crowd.net.traffic(PacketType.PRIVATE)
        val undelivered = pairs.filter { it.first.messages.single().status != MsgStatus.DELIVERED }.map { it.first.name }
        println(
            "Chat burst, ${pairs.size} messages at once among 200 phones, 5 reports 2 s later:\n" +
                "  reports confirmed by staff after ${"%.2f".format(confirmedMs / 1000.0)} s\n" +
                "  PRIVATE after 60 s: ${chat.packets} packets, ${chat.transmissions} transmissions\n" +
                "  dropped ${states.sumOf { it.dropped }}, queue full ${states.sumOf { it.queueFull }}\n" +
                "  delivered within 60 s: ${pairs.size - undelivered.size} of ${pairs.size}",
        )
        assertTrue("not delivered within 60 s: $undelivered", undelivered.isEmpty())
        for ((_, b) in pairs) assertEquals(b.name, 1, b.messages.size)
    }
}
