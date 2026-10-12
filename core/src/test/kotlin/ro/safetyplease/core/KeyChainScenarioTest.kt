package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.incidents.ReportDraft
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.LinkInfo
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.mesh.V10Node
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.hexToBytes

/**
 * The key chain on the simulated radio (PROTOCOL.md, sections 6.1, 6.4, 6.8 and 9): roles proven in HELLO, ACKs
 * signed under shift certificates, revocations, the day's report key, and phones of older versions next to it all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyChainScenarioTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private inner class World(val scope: TestScope) {
        val clock = TestClock(scope.testScheduler)
        val net = SimNet(scope.backgroundScope)
        val event = TestEvent(dayKey = true)
        private var seed = 700L

        fun phone(name: String, nickname: String? = null) =
            TestPhone(name, scope, net, clock, tmp.root, event, seed++, nicknameInHello = nickname != null).also {
                it.engine.setNickname(nickname)
            }

        fun shift(name: String, role: Int, team: String, until: Long = TestEvent.DAY, opensReports: Boolean = true) =
            phone(name).also { it.activate(event.shift(role, team, until = until, opensReports = opensReports)) }

        /** [a] and [b] hear each other only through the others. */
        fun apart(a: String, b: String) {
            net.outOfRange += setOf(a, b)
        }
    }

    private fun draft() = ReportDraft(IncidentCategory.MEDICAL, Severity.URGENT, "main-stage", null, null, "A căzut", null)

    private fun TestScope.started(w: World) {
        w.net.start()
        advanceTimeBy(30_000)
    }

    private fun TestPhone.link(other: TestPhone): LinkInfo = engine.state.value.links.single { it.peerId == other.nodeId }

    private fun TestPhone.drops(reason: DropReason): Long = engine.state.value.counters.drops[reason] ?: 0

    private fun TestPhone.status(): Int = myReports.single().status

    @Test
    fun rolesAndStaffNamesCountOnlyOnceACertificateProvesThem() = runTest {
        val w = World(this)
        val participant = w.phone("P")
        val anchor = w.shift("A", CertRole.ANCHOR, "Ancora bar")
        val medic = w.phone("M", "Medic Ana").also { it.activate(w.event.shift(CertRole.STAFF, "Medical 1")) }
        val fake = w.phone("F", "Medic").also { it.engine.setRole(staff = false, anchor = true) }
        started(w)

        assertTrue(participant.link(anchor).verified)
        assertTrue(participant.link(medic).verified)
        assertEquals("Medic Ana", participant.link(medic).nickname)
        assertFalse(participant.link(fake).verified)
        assertNull("a staff name from a phone that proves nothing", participant.link(fake).nickname)
        assertFalse("a participant has no role to prove", medic.link(participant).verified)
    }

    @Test
    fun aStolenPhoneSignsOnlyDuringItsShift() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val relay = w.phone("X")
        val medic = w.shift("M", CertRole.STAFF, "Medical 1", until = 120)
        w.apart("R", "M")
        started(w)

        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val id = reporter.myReports.single().incidentId
        medic.incidents.acknowledge(id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.ACKNOWLEDGED, reporter.status())

        // past the shift and the clock skew: what the phone still signs goes nowhere
        advanceTimeBy((120 + 600 + 30) * 1000L)
        medic.incidents.resolve(id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.ACKNOWLEDGED, reporter.status())
        assertTrue(relay.drops(DropReason.UNTRUSTED) > 0)
        assertEquals(0L, relay.drops(DropReason.MALFORMED))
    }

    @Test
    fun aRevokedCertificateIsRejectedEverywhere() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val relay = w.phone("X")
        val card = w.event.shift(CertRole.STAFF, "Medical 1")
        val medic = w.phone("M").also { it.activate(card) }
        val console = w.shift("K", CertRole.CONTROL, "Control")
        w.apart("R", "M")
        w.apart("R", "K")
        started(w)

        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val id = reporter.myReports.single().incidentId
        medic.incidents.acknowledge(id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.ACKNOWLEDGED, reporter.status())

        assertEquals(listOf(card.certificate.id), console.revocations.add(w.event.revocation(card.certificate)))
        advanceTimeBy(10_000)
        for (phone in listOf(reporter, relay, medic)) assertTrue(phone.name, phone.keyChain.isRevoked(card.certificate.id))

        medic.incidents.onScene(id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.ACKNOWLEDGED, reporter.status())
        assertTrue(relay.drops(DropReason.UNTRUSTED) > 0)
        // the console's own statuses still go through
        console.incidents.close(id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.CLOSED_BY_CONTROL, reporter.status())
    }

    @Test
    fun aTeamWithoutTheDaysKeyRelaysReportsItCantOpen() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val team = w.shift("T", CertRole.STAFF, "Securitate 1", opensReports = false)
        val lead = w.shift("L", CertRole.STAFF, "Medical 1")
        val legacy = w.phone("S").also { it.staffSecret = w.event.secret }
        w.apart("R", "L")
        w.net.outOfRange += setOf("S", "L")
        w.net.outOfRange += setOf("S", "T")
        started(w)

        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        assertTrue(team.staffIncidents.isEmpty())
        assertTrue("sealed to the day's key, which SPS1 codes don't hold", legacy.staffIncidents.isEmpty())
        assertEquals(1, lead.staffIncidents.size)
        assertEquals(AckStatus.RECEIVED, reporter.status())
    }

    @Test
    fun oneZeroPhonesKeepWorkingNextToCertificates() = runTest {
        val w = World(this)
        val reporter = w.phone("R")
        val medic = w.shift("M", CertRole.STAFF, "Medical 1")
        val old = V10Node(backgroundScope, w.net, "V", 1L, w.clock, { w.event.staffCrypto.verifyAck(it) })
        started(w)

        reporter.incidents.report(draft())
        advanceTimeBy(10_000)
        val id = reporter.myReports.single().incidentId
        medic.incidents.acknowledge(id)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.ACKNOWLEDGED, reporter.status())
        assertTrue("the report crosses the 1.0 phone", old.received.any { it.type == PacketType.INCIDENT_REPORT })
        assertTrue("no ACK under a certificate reaches it", old.received.none { it.type == PacketType.INCIDENT_ACK })

        // the build's shared key still signs for everyone, the 1.0 phone included
        val legacyAck = w.event.staffCrypto.signAck(id.hexToBytes(), AckStatus.RESOLVED, w.clock.wallMs() / 1000, "Medical 1", w.event.secret)
        medic.engine.publishAck(legacyAck)
        advanceTimeBy(10_000)
        assertEquals(AckStatus.RESOLVED, old.ackStatus(id.hexToBytes()))
        assertEquals(AckStatus.RESOLVED, reporter.status())
        assertEquals(0, old.malformed)
        for (phone in listOf(reporter, medic)) assertEquals(0L, phone.drops(DropReason.MALFORMED))
        assertFalse(IncidentAck.decode(legacyAck.encode())!!.certified)
    }
}
