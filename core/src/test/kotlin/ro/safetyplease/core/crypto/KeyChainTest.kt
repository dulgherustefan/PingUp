package ro.safetyplease.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.mesh.Candidate
import ro.safetyplease.core.mesh.ConnectionPolicy
import ro.safetyplease.core.mesh.LinkLimits
import ro.safetyplease.core.mesh.Me
import ro.safetyplease.core.mesh.ProvenRoles
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.AckFlags
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.HelloProof
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.util.Clock

class KeyChainTest {
    private class Wall(var sec: Long) : Clock {
        override fun wallMs(): Long = sec * 1000
        override fun monoMs(): Long = sec * 1000
    }

    private val t0 = 1_760_000_000L
    private val clock = Wall(t0)
    private val root = testCrypto.signKeyPair()
    private val organiser = testCrypto.signKeyPair()
    private val event = testCrypto.signKeyPair()
    private val legacyBox = testCrypto.boxKeyPair()
    private val legacySign = testCrypto.signKeyPair()
    private val organiserCert = issue(CertRole.ORGANISER, organiser.publicKey, root, 0, 0xFFFFFFFFL, "Org")
    private val eventCert = issue(CertRole.EVENT, event.publicKey, organiser, t0 - 3600, t0 + 86_400, "Fest")
    private val dayBox = testCrypto.boxKeyPair()
    private val dayCert = issue(CertRole.REPORT_KEY, dayBox.publicKey, event, t0 - 3600, t0 + 3600, "")
    private val chain = KeyChain(
        testCrypto, clock, legacySign.publicKey, root.publicKey, listOf(eventCert, organiserCert), listOf(dayCert),
    )
    private val staff = StaffCrypto(testCrypto, StaffPublicKeys(legacyBox.publicKey, legacySign.publicKey), chain)
    private val id = ByteArray(16) { it.toByte() }

    private fun issue(role: Int, key: ByteArray, by: KeyPair, from: Long, until: Long, name: String) =
        Certificate.issue(testCrypto, role, key, from, until, name, by)

    private class Shift(val key: KeyPair, val cert: Certificate) {
        fun secret() = StaffSecretKeys(null, key, cert)
    }

    private fun shift(role: Int, team: String, from: Long = t0 - 60, until: Long = t0 + 3600, by: KeyPair = event): Shift {
        val key = testCrypto.signKeyPair()
        return Shift(key, issue(role, key.publicKey, by, from, until, team))
    }

    private fun ack(s: Shift, status: Int = AckStatus.ACKNOWLEDGED, team: String = s.cert.name, flags: Int = 0, time: Long = clock.sec) =
        staff.signAck(id, status, time, team, s.secret(), flags).encode()

    @Test
    fun aShiftLeadsToTheRootThroughTheEventAndTheOrganiser() {
        val medic = shift(CertRole.STAFF, "Medical 1")
        assertEquals(CertStatus.VALID, chain.status(medic.cert))
        assertEquals(CertStatus.VALID, chain.status(eventCert))
        val otherEvent = testCrypto.signKeyPair()
        assertEquals(CertStatus.UNKNOWN, chain.status(shift(CertRole.STAFF, "Medical 1", by = otherEvent).cert))
        // the organiser can't sign a shift itself, nor a shift a shift
        assertEquals(CertStatus.UNKNOWN, chain.status(shift(CertRole.STAFF, "Medical 1", by = organiser).cert))
        val forged = medic.cert.let { Certificate(it.role, it.key, it.validFrom, it.validUntil + 3600, it.issuer, it.name, it.signature) }
        assertEquals(CertStatus.FORGED, chain.status(forged))
    }

    @Test
    fun chainCertificatesThatDontLeadToTheRootAreIgnored() {
        val stranger = KeyChain(testCrypto, clock, legacySign.publicKey, testCrypto.signKeyPair().publicKey, listOf(eventCert, organiserCert))
        assertEquals(CertStatus.UNKNOWN, stranger.status(shift(CertRole.STAFF, "Medical 1").cert))
        val noRoot = KeyChain(testCrypto, clock, legacySign.publicKey, null, listOf(eventCert, organiserCert))
        assertEquals(CertStatus.UNKNOWN, noRoot.status(eventCert))
    }

    @Test
    fun aShiftHoldsOnlyInItsWindowWithTenMinutesOfSkew() {
        val medic = shift(CertRole.STAFF, "Medical 1", from = t0, until = t0 + 3600)
        assertEquals(CertStatus.VALID, chain.status(medic.cert, t0 - KeyChain.SKEW_S))
        assertEquals(CertStatus.OUTSIDE_WINDOW, chain.status(medic.cert, t0 - KeyChain.SKEW_S - 1))
        assertEquals(CertStatus.VALID, chain.status(medic.cert, t0 + 3600 + KeyChain.SKEW_S))
        assertEquals(CertStatus.OUTSIDE_WINDOW, chain.status(medic.cert, t0 + 3600 + KeyChain.SKEW_S + 1))
        // a shift past the event's end ends with it
        assertEquals(CertStatus.OUTSIDE_WINDOW, chain.status(shift(CertRole.STAFF, "M", until = t0 + 9 * 86_400).cert, t0 + 2 * 86_400))
    }

    @Test
    fun revocationsAreSignedByTheChainAndReachEveryShiftBelowARevokedKey() {
        val medic = shift(CertRole.STAFF, "Medical 1")
        val other = shift(CertRole.STAFF, "Medical 2")
        val rogue = Revocation.sign(testCrypto, testCrypto.signKeyPair(), t0, listOf(medic.cert.id)).encode()
        assertEquals(Verdict.REFUSED, chain.checkAnnouncement(rogue))
        assertNull(chain.revoke(rogue))

        val revocation = Revocation.sign(testCrypto, event, t0, listOf(medic.cert.id)).encode()
        assertEquals(listOf(medic.cert.id), chain.revoke(revocation))
        assertEquals(emptyList<Long>(), chain.revoke(revocation))
        assertEquals(CertStatus.REVOKED, chain.status(medic.cert))
        assertEquals(CertStatus.VALID, chain.status(other.cert))
        val tampered = revocation.clone().also { it[20] = (it[20] + 1).toByte() }
        assertEquals(Verdict.INVALID, chain.checkAnnouncement(tampered))

        assertNotNull(chain.revoke(Revocation.sign(testCrypto, organiser, t0, listOf(eventCert.id)).encode()))
        assertEquals(CertStatus.REVOKED, chain.status(other.cert))
        // the event's key can't revoke anything any more either
        assertEquals(Verdict.REFUSED, chain.checkAnnouncement(Revocation.sign(testCrypto, event, t0, listOf(1L)).encode()))
    }

    @Test
    fun legacyAcksStillVerifyAgainstTheSharedKey() {
        val legacy = StaffSecretKeys(legacyBox, legacySign)
        val payload = staff.signAck(id, AckStatus.RESOLVED, t0, "Medical 1", legacy).encode()
        assertEquals(Verdict.VALID, chain.checkAck(payload))
        assertEquals(Verdict.INVALID, chain.checkAck(payload.clone().also { it[17] = 9 }))
    }

    @Test
    fun aTeamSignsOnlyItsOwnTakesAndAnAnchorNothing() {
        val medic = shift(CertRole.STAFF, "Medical 1")
        val payload = ack(medic)
        assertTrue(IncidentAck.decode(payload)!!.certified)
        assertArrayEquals(medic.cert.encode(), IncidentAck.decode(payload)!!.certificate!!.encode())
        assertEquals(Verdict.VALID, chain.checkAck(payload))
        assertEquals(Verdict.VALID, chain.checkAck(ack(medic, AckStatus.CANCELLED)))
        assertEquals(Verdict.REFUSED, chain.checkAck(ack(medic, team = "Medical 2")))
        for (status in listOf(AckStatus.CLOSED_BY_CONTROL, AckStatus.UNFOUNDED, AckStatus.REOPENED)) {
            assertEquals(Verdict.REFUSED, chain.checkAck(ack(medic, status)))
        }
        assertEquals(Verdict.REFUSED, chain.checkAck(ack(medic, flags = AckFlags.ASSIGNED)))
        assertEquals(Verdict.REFUSED, chain.checkAck(ack(shift(CertRole.ANCHOR, "Ancora bar"))))

        val control = shift(CertRole.CONTROL, "Control")
        assertEquals(Verdict.VALID, chain.checkAck(ack(control, AckStatus.CLOSED_BY_CONTROL)))
        assertEquals(Verdict.VALID, chain.checkAck(ack(control, team = "Medical 2", flags = AckFlags.ASSIGNED)))

        for (i in payload.indices) {
            val forged = payload.clone().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFalse("byte $i", chain.checkAck(forged) == Verdict.VALID)
        }
    }

    @Test
    fun aStolenShiftSignsNothingAfterItEndsNorAheadOfTheClock() {
        val medic = shift(CertRole.STAFF, "Medical 1", until = t0 + 3600)
        assertEquals(Verdict.REFUSED, chain.checkAck(ack(medic, time = t0 + KeyChain.SKEW_S + 1)))
        assertEquals(Verdict.REFUSED, chain.checkAck(ack(medic, time = t0 - 2 * 3600)))
        clock.sec = t0 + 3600 + KeyChain.SKEW_S + 1
        assertEquals(Verdict.REFUSED, chain.checkAck(ack(medic, time = t0 + 1800)))
        assertEquals(Verdict.REFUSED, chain.checkAck(ack(medic)))
    }

    @Test
    fun aHelloProofAnswersOneNonceForOneNode() {
        val anchor = shift(CertRole.ANCHOR, "Ancora bar")
        val nonce = testCrypto.random(Hello.NONCE_SIZE)
        assertNull(chain.helloProof(nonce, 7L))
        chain.credential = Credential(anchor.cert, anchor.key)
        val proof = chain.helloProof(nonce, 7L)!!
        assertEquals(NodeFlags.ANCHOR, chain.provenRoles(proof, nonce, 7L))
        assertEquals(0, chain.provenRoles(proof, testCrypto.random(Hello.NONCE_SIZE), 7L))
        assertEquals(0, chain.provenRoles(proof, nonce, 8L))
        val control = shift(CertRole.CONTROL, "Control")
        val signed = testCrypto.sign(Hello.proofMessage(nonce, 7L), control.key.secretKey)
        assertEquals(NodeFlags.STAFF, chain.provenRoles(HelloProof(control.cert, signed), nonce, 7L))
        // a certificate of another event proves nothing
        val stranger = shift(CertRole.STAFF, "Medical 1", by = testCrypto.signKeyPair())
        val strangerSigned = testCrypto.sign(Hello.proofMessage(nonce, 7L), stranger.key.secretKey)
        assertEquals(0, chain.provenRoles(HelloProof(stranger.cert, strangerSigned), nonce, 7L))
        clock.sec = t0 + 2 * 86_400
        assertNull("no proof outside the shift", chain.helloProof(nonce, 7L))
    }

    @Test
    fun reportsAreSealedToTheDaysKeyAndOnlyItsHoldersOpenThem() {
        assertArrayEquals(dayBox.publicKey, chain.reportKey())
        val sealed = staff.sealReport("body".toByteArray())
        val medic = shift(CertRole.STAFF, "Medical 1")
        assertNull(staff.openReport(sealed, medic.secret()))
        assertNull(staff.openReport(sealed, StaffSecretKeys(legacyBox, legacySign)))
        val lead = StaffSecretKeys(dayBox, medic.key, medic.cert, legacyBox)
        assertEquals("body", String(staff.openReport(sealed, lead)!!))
        // outside the planned days the build's key takes over, and the lead's copy of it still opens
        clock.sec = t0 + 3 * 3600
        assertNull(chain.reportKey())
        assertEquals("late", String(staff.openReport(staff.sealReport("late".toByteArray()), lead)!!))
    }

    @Test
    fun anSps2CodeCarriesTheShiftAndOnlyTheKeysItsHolderNeeds() {
        val seed = testCrypto.random(32)
        val shiftKey = testCrypto.signKeyPairFromSeed(seed)
        val cert = issue(CertRole.CONTROL, shiftKey.publicKey, event, t0, t0 + 3600, "Control")
        val daySeed = testCrypto.random(32)
        val day = issue(CertRole.REPORT_KEY, testCrypto.boxKeyPairFromSeed(daySeed).publicKey, event, t0, t0 + 3600, "")
        val card = ShiftCard(cert, seed, day, daySeed, null, "main-stage")
        val text = QrCodes.encodeShift(card)
        assertTrue(text.startsWith("SPS2.") && QrCodes.isStaff(text) && text.length <= 640)
        val back = QrCodes.decodeShift(text)!!
        assertEquals(StaffRole.CONTROL, back.role)
        assertEquals("Control", back.teamName)
        assertEquals("main-stage", back.zone)
        assertEquals(text, QrCodes.encodeShift(back))
        val (credential, secret) = staff.credentialFrom(back)!!
        assertArrayEquals(cert.encode(), credential.certificate.encode())
        assertNotNull(secret!!.box)

        assertNull("seed of another key", staff.credentialFrom(ShiftCard(cert, testCrypto.random(32), null, null, null, "")))
        assertNull(staff.credentialFrom(ShiftCard(cert, seed, day, testCrypto.random(32), null, "")))
        assertNull("another build's key", staff.credentialFrom(ShiftCard(cert, seed, null, null, testCrypto.random(32), "")))
        val anchor = issue(CertRole.ANCHOR, shiftKey.publicKey, event, t0, t0 + 3600, "Ancora bar")
        assertNull("an anchor holds no staff secret", staff.credentialFrom(ShiftCard(anchor, seed, null, null, null, ""))!!.second)
        // a day's report key is no shift, and a report seed comes only with its key
        assertNull(QrCodes.decodeShift(QrCodes.encodeShift(ShiftCard(day, seed, null, null, null, ""))))
        assertNull(QrCodes.decodeShift(text.dropLast(4)))
    }

    @Test
    fun revocationCodesRoundTrip() {
        val payload = Revocation.sign(testCrypto, event, t0, listOf(1L, 2L)).encode()
        assertArrayEquals(payload, QrCodes.decodeRevocation(QrCodes.encodeRevocation(payload)))
        val full = Revocation.sign(testCrypto, event, t0, List(Revocation.MAX_IDS) { it.toLong() }).encode()
        assertTrue(QrCodes.encodeRevocation(full).length <= 512)
        assertNull(Revocation.decode(payload + 0))
        assertEquals(listOf(1L, 2L), Revocation.decode(payload)!!.ids)
    }

    @Test
    fun namesThatPassForStaffAreReserved() {
        for (name in listOf("STAFF", "Medic", "medic șef", "Securitate 2", "St4ff", "Poliția", "Ping Up", "ADMIN")) {
            assertTrue(name, ReservedNames.imitatesStaff(name))
        }
        for (name in listOf("Ana", "Mihai", "Bogdan", "Ioana P.")) assertFalse(name, ReservedNames.imitatesStaff(name))
    }

    @Test
    fun anUnprovenAnchorIsRankedLikeAnyPeer() {
        val open = NodeFlags.ACCEPTS_CONNECTIONS or NodeFlags.PREFIX_IN_HELLO
        val me = Me(prefix = 0, nodePrefix = 0, canAdvertise = true)
        val fake = Candidate("F", 1, NodeFlags.ANCHOR or open, rssi = -50, sinceMs = 0, lastSeenMs = 0)
        val real = Candidate("A", 2, NodeFlags.ANCHOR or open, rssi = -80, sinceMs = 0, lastSeenMs = 0)
        val plain = Candidate("P", 3, open, rssi = -60, sinceMs = 0, lastSeenMs = 0)
        fun pick(vararg c: Candidate) = ConnectionPolicy.pick(me, LinkLimits.DEFAULT, c.toList(), emptyList(), 0) { false }!!.address
        assertEquals("F", pick(fake, real, plain))
        val unproven = ProvenRoles.candidate(fake, ProvenRoles.UNPROVEN_MS, 0)
        assertEquals("A", pick(unproven, real, plain))
        assertEquals("F", pick(ProvenRoles.candidate(fake, ProvenRoles.UNPROVEN_MS, ProvenRoles.UNPROVEN_MS), real))
        assertEquals(open, ProvenRoles.linkFlags(NodeFlags.ANCHOR or open, 0))
    }
}
