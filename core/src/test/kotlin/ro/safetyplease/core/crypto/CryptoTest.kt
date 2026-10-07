package ro.safetyplease.core.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentReportCodec
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.utf8

/** Libsodium real, incarcat pe JVM prin lazysodium-java. */
val testCrypto: Crypto by lazy { SodiumCrypto(LazySodiumJava(SodiumJava())) }

class CryptoTest {
    private val crypto = testCrypto

    @Test
    fun boxRoundTripBetweenTwoIdentities() {
        val alice = Identity.generate(crypto)
        val bob = Identity.generate(crypto)
        val plain = "ne vedem la scena principală".utf8()
        val sealed = crypto.box(plain, bob.box.publicKey, alice.box.secretKey)
        assertEquals(plain.size + Crypto.BOX_OVERHEAD, sealed.size)
        assertArrayEquals(plain, crypto.boxOpen(sealed, alice.box.publicKey, bob.box.secretKey))
    }

    @Test
    fun boxUsesFreshNonceEveryTime() {
        val a = Identity.generate(crypto)
        val b = Identity.generate(crypto)
        val one = crypto.box(byteArrayOf(1), b.box.publicKey, a.box.secretKey)
        val two = crypto.box(byteArrayOf(1), b.box.publicKey, a.box.secretKey)
        assertFalse(one.contentEquals(two))
    }

    @Test
    fun boxRejectsTamperingWrongSenderAndWrongRecipient() {
        val alice = Identity.generate(crypto)
        val bob = Identity.generate(crypto)
        val eve = Identity.generate(crypto)
        val sealed = crypto.box("secret".utf8(), bob.box.publicKey, alice.box.secretKey)
        assertNull(crypto.boxOpen(sealed.clone().also { it[30] = (it[30] + 1).toByte() }, alice.box.publicKey, bob.box.secretKey))
        assertNull("expeditor fals", crypto.boxOpen(sealed, eve.box.publicKey, bob.box.secretKey))
        assertNull("alt destinatar", crypto.boxOpen(sealed, alice.box.publicKey, eve.box.secretKey))
        assertNull(crypto.boxOpen(ByteArray(10), alice.box.publicKey, bob.box.secretKey))
    }

    @Test
    fun sealedBoxOpensOnlyWithRecipientKey() {
        val staff = crypto.boxKeyPair()
        val other = crypto.boxKeyPair()
        val plain = "raport".utf8()
        val sealed = crypto.seal(plain, staff.publicKey)
        assertEquals(plain.size + IncidentReportCodec.SEAL_OVERHEAD, sealed.size)
        assertEquals(IncidentReportCodec.SEAL_OVERHEAD, Crypto.SEAL_OVERHEAD)
        assertArrayEquals(plain, crypto.sealOpen(sealed, staff.publicKey, staff.secretKey))
        assertNull(crypto.sealOpen(sealed, other.publicKey, other.secretKey))
        assertNull(crypto.sealOpen(ByteArray(5), staff.publicKey, staff.secretKey))
    }

    @Test
    fun signaturesVerifyAndRejectChanges() {
        val keys = crypto.signKeyPair()
        val message = "ack".utf8()
        val sig = crypto.sign(message, keys.secretKey)
        assertEquals(IncidentAck.SIGNATURE_SIZE, sig.size)
        assertTrue(crypto.verify(sig, message, keys.publicKey))
        assertFalse(crypto.verify(sig, "ack!".utf8(), keys.publicKey))
        assertFalse(crypto.verify(sig, message, crypto.signKeyPair().publicKey))
        assertFalse(crypto.verify(ByteArray(3), message, keys.publicKey))
    }

    @Test
    fun seedsGiveDeterministicKeys() {
        val seed = ByteArray(32) { it.toByte() }
        assertArrayEquals(crypto.boxKeyPairFromSeed(seed).publicKey, crypto.boxKeyPairFromSeed(seed).publicKey)
        // vector Ed25519 din RFC 8032 (test 1)
        val rfcSeed = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60".hexToBytes()
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            crypto.signKeyPairFromSeed(rfcSeed).publicKey.toHex(),
        )
    }

    @Test
    fun nodeIdIsDerivedFromBothPublicKeys() {
        val id = Identity.generate(crypto)
        val expected = sha256(id.sign.publicKey + id.box.publicKey).copyOf(8).toHex()
        assertEquals(expected, id.nodeId.toHex())
        assertNotEquals(id.nodeId, Identity.generate(crypto).nodeId)
    }

    @Test
    fun identitySurvivesExportImport() {
        val id = Identity.generate(crypto)
        val back = Identity.import(id.export())!!
        assertEquals(id.nodeId, back.nodeId)
        assertArrayEquals(id.box.secretKey, back.box.secretKey)
        assertArrayEquals(id.sign.secretKey, back.sign.secretKey)
        assertNull(Identity.import(ByteArray(10)))
    }

    @Test
    fun friendQrRoundTrip() {
        val id = Identity.generate(crypto)
        val text = QrCodes.encodeFriend(FriendCard("Ioana 🎪", id.box.publicKey, id.sign.publicKey))
        assertTrue(text.startsWith("SPF1."))
        val card = QrCodes.decodeFriend(text)!!
        assertEquals("Ioana 🎪", card.nickname)
        assertEquals(id.nodeId, card.nodeId)
        assertNull(QrCodes.decodeFriend("SPF1.###"))
        assertNull(QrCodes.decodeFriend("https://example.com"))
        assertNull(QrCodes.decodeFriend(text.dropLast(4)))
        assertNull("QR de staff nu e prieten", QrCodes.decodeFriend(text.replace("SPF1.", "SPS1.")))
    }

    @Test
    fun staffQrRoundTrip() {
        val card = StaffCard(ByteArray(32) { 1 }, ByteArray(32) { 2 }, StaffRole.ANCHOR, "Medical 1", "main-stage")
        val text = QrCodes.encodeStaff(card)
        assertTrue(QrCodes.isStaff(text))
        val back = QrCodes.decodeStaff(text)!!
        assertEquals(StaffRole.ANCHOR, back.role)
        assertEquals("Medical 1", back.teamName)
        assertEquals("main-stage", back.zone)
        assertArrayEquals(card.signSeed, back.signSeed)
        assertNull(QrCodes.decodeStaff(text.dropLast(3)))
    }

    @Test
    fun staffQrFromPythonToolDecodesAndDerivesTheSameKeys() {
        // produs de tools/gen_staff_keys.py pentru semintele 00..1f si 20..3f
        val text = "SPS1.AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-PwIKQW5jb3JhIGJhcgNiYXI"
        val card = QrCodes.decodeStaff(text)!!
        assertEquals(StaffRole.ANCHOR, card.role)
        assertEquals("Ancora bar", card.teamName)
        assertEquals("bar", card.zone)
        assertEquals(
            "4701d08488451f545a409fb58ae3e58581ca40ac3f7f114698cd71deac73ca01",
            crypto.boxKeyPairFromSeed(card.boxSeed).publicKey.toHex(),
        )
        assertEquals(
            "29acbae141bccaf0b22e1a94d34d0bc7361e526d0bfe12c89794bc9322966dd7",
            crypto.signKeyPairFromSeed(card.signSeed).publicKey.toHex(),
        )
        assertEquals(text, QrCodes.encodeStaff(card))
    }

    @Test
    fun bundledDemoSeedsMatchBundledStaffPublicKeys() {
        fun field(file: String, name: String): ByteArray {
            val json = java.io.File(file).readText()
            return Regex("\"$name\"\\s*:\\s*\"([0-9a-f]+)\"").find(json)!!.groupValues[1].hexToBytes()
        }
        val public = StaffPublicKeys(
            field("../app/src/main/assets/staff_public.json", "box"),
            field("../app/src/main/assets/staff_public.json", "sign"),
        )
        val secret = StaffCrypto(crypto, public).secretFromSeeds(
            field("../app/src/debug/assets/demo_staff.json", "boxSeed"),
            field("../app/src/debug/assets/demo_staff.json", "signSeed"),
        )
        assertNotNull("modul demo nu ar putea activa staff fara QR", secret)
    }

    private fun staff(): Pair<StaffCrypto, StaffSecretKeys> {
        val boxSeed = crypto.random(32)
        val signSeed = crypto.random(32)
        val public = StaffPublicKeys(
            crypto.boxKeyPairFromSeed(boxSeed).publicKey,
            crypto.signKeyPairFromSeed(signSeed).publicKey,
        )
        val sc = StaffCrypto(crypto, public)
        return sc to sc.secretFromSeeds(boxSeed, signSeed)!!
    }

    @Test
    fun staffSeedsMustMatchEmbeddedPublicKeys() {
        val (sc, _) = staff()
        assertNull(sc.secretFromSeeds(crypto.random(32), crypto.random(32)))
    }

    @Test
    fun reportIsReadableOnlyByStaff() {
        val (sc, secret) = staff()
        val (_, otherSecret) = staff()
        val sealed = sc.sealReport("corp".utf8())
        assertArrayEquals("corp".utf8(), sc.openReport(sealed, secret))
        assertNull(sc.openReport(sealed, otherSecret))
    }

    @Test
    fun ackSignatureCoversEveryField() {
        val (sc, secret) = staff()
        val id = crypto.random(INCIDENT_ID_SIZE)
        val payload = sc.signAck(id, AckStatus.ACKNOWLEDGED, 1_760_000_000L, "Medical 1", secret).encode()
        assertTrue(sc.verifyAck(payload))
        assertNotNull(IncidentAck.decode(payload))
        for (i in 0 until payload.size - IncidentAck.SIGNATURE_SIZE) {
            val forged = payload.clone().also { it[i] = (it[i] + 1).toByte() }
            assertFalse("octet $i", sc.verifyAck(forged))
        }
        val (otherStaff, _) = staff()
        assertFalse("alta cheie de staff", otherStaff.verifyAck(payload))
        assertFalse(sc.verifyAck(ByteArray(20)))
    }
}
