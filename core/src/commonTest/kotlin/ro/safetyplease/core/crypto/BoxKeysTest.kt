package ro.safetyplease.core.crypto

import ro.safetyplease.core.protocol.IncidentBody
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Small-order X25519 keys: libsodium refuses them, so they are refused where they come in, and nothing throws. */
class BoxKeysTest {
    private val me = testCrypto.boxKeyPair()

    /** Each listed encoding, and the same with the top bit set, which isn't part of the point. */
    private val smallOrder = BoxKeys.SMALL_ORDER.flatMap { key -> listOf(key, key.copyOf().also { it[31] = (it[31].toInt() or 0x80).toByte() }) }

    @Test
    fun boxAndSealToASmallOrderKeySayNullInsteadOfThrowing() {
        for (key in smallOrder) {
            assertFalse(BoxKeys.acceptable(key), key.toHex())
            // the list must be libsodium's own: each of its keys is one libsodium refuses
            assertNull(testCrypto.box(byteArrayOf(1, 2, 3), key, me.secretKey), key.toHex())
            assertNull(testCrypto.seal(byteArrayOf(1, 2, 3), key), key.toHex())
        }
        val other = testCrypto.boxKeyPair().publicKey
        assertTrue(BoxKeys.acceptable(other))
        assertNotNull(testCrypto.box(byteArrayOf(1), other, me.secretKey))
        assertNotNull(testCrypto.seal(byteArrayOf(1), other))
    }

    @Test
    fun aKeyOfTheWrongSizeIsRefusedToo() {
        assertFalse(BoxKeys.acceptable(null))
        assertFalse(BoxKeys.acceptable(ByteArray(31) { 9 }))
        assertNull(testCrypto.box(byteArrayOf(1), ByteArray(31) { 9 }, me.secretKey))
        assertNull(testCrypto.seal(byteArrayOf(1), ByteArray(33) { 9 }))
    }

    @Test
    fun aFriendCodeWithASmallOrderKeyIsNoFriendCode() {
        val sign = testCrypto.signKeyPair().publicKey
        for (key in smallOrder) assertNull(QrCodes.decodeFriend(QrCodes.encodeFriend(FriendCard("X", key, sign))), key.toHex())
        val good = testCrypto.boxKeyPair().publicKey
        assertContentEquals(good, QrCodes.decodeFriend(QrCodes.encodeFriend(FriendCard("Ana", good, sign)))?.boxKey)
    }

    @Test
    fun aReportsSmallOrderReplyKeyReadsAsNone() {
        fun body(key: ByteArray) = IncidentBody(IncidentCategory.MEDICAL, Severity.URGENT, 1L, "scena", null, null, "Nu respiră", null, replyKey = key)
        for (key in smallOrder) assertNull(IncidentBody.decode(body(key).encode())!!.replyKey, key.toHex())
        val key = testCrypto.boxKeyPair().publicKey
        assertContentEquals(key, IncidentBody.decode(body(key).encode())!!.replyKey)
    }

    @Test
    fun aReportStillGoesWhenTheDaysKeyIsOneLibsodiumRefuses() {
        val start = 1_760_000_000L
        val root = testCrypto.signKeyPair()
        val organiser = testCrypto.signKeyPair()
        val event = testCrypto.signKeyPair()
        val issuers = listOf(
            Certificate.issue(testCrypto, CertRole.ORGANISER, organiser.publicKey, 0, 0xFFFFFFFFL, "Org", root),
            Certificate.issue(testCrypto, CertRole.EVENT, event.publicKey, start - 3600, start + 86_400, "Fest", organiser),
        )
        // signed down the chain, but a point of small order: an organiser's tool gone wrong
        val broken = Certificate.issue(testCrypto, CertRole.REPORT_KEY, ByteArray(32), start - 3600, start + 86_400, "", event)
        val clock = object : Clock {
            override fun wallMs() = start * 1000
            override fun monoMs() = 0L
        }
        val shared = testCrypto.boxKeyPair()
        val sign = testCrypto.signKeyPair()
        val chain = KeyChain(testCrypto, clock, sign.publicKey, root.publicKey, issuers, listOf(broken))
        assertContentEquals(ByteArray(32), chain.reportKey())
        val staff = StaffCrypto(testCrypto, StaffPublicKeys(shared.publicKey, sign.publicKey), chain)
        val sealed = assertNotNull(staff.sealReport("ajutor".encodeToByteArray()), "sealed to the shared key instead")
        assertContentEquals("ajutor".encodeToByteArray(), staff.openReport(sealed, StaffSecretKeys(null, sign, legacyBox = shared)))
        assertNull(StaffCrypto(testCrypto, StaffPublicKeys(ByteArray(32), sign.publicKey), chain).sealReport(byteArrayOf(1)), "no key left")
    }
}
