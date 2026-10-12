package ro.safetyplease.core.crypto

import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.MalformedException
import ro.safetyplease.core.protocol.WireReader
import ro.safetyplease.core.protocol.WireWriter
import ro.safetyplease.core.protocol.parseOrNull
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.util.utf8
import kotlin.io.encoding.Base64

class FriendCard(val nickname: String, val boxKey: ByteArray, val signKey: ByteArray) {
    val nodeId: Long get() = Identity.nodeIdOf(signKey, boxKey)
}

/** [code] is the role byte in the staff QR. CONTROL keeps the staff keys like STAFF and runs the control-room console. */
enum class StaffRole(val code: Int) { STAFF(1), ANCHOR(2), CONTROL(3) }

/** Staff QR payload: seeds the keys are derived from, the role, and the team or zone. */
class StaffCard(
    val boxSeed: ByteArray,
    val signSeed: ByteArray,
    val role: StaffRole,
    val teamName: String,
    val zone: String,
)

/**
 * SPS2 staff QR: a shift [certificate], the seed of the key it names, and for a console or a role lead the day's
 * [reportKey] with its seed. [legacyBoxSeed] opens reports sealed to the build's key, while phones that don't know the
 * day's key are still around.
 */
class ShiftCard(
    val certificate: Certificate,
    val keySeed: ByteArray,
    val reportKey: Certificate?,
    val reportSeed: ByteArray?,
    val legacyBoxSeed: ByteArray?,
    val zone: String,
) {
    val role: StaffRole get() = StaffRole.entries.first { it.code == certificate.role }
    val teamName: String get() = certificate.name
}

/** QR formats, versioned by prefix. tools/gen_staff_keys.py produces the same format. */
object QrCodes {
    private const val FRIEND_PREFIX = "SPF1."

    /** The build's shared seeds, as before certificates; still read while phones move to SPS2. */
    private const val STAFF_PREFIX = "SPS1."
    private const val SHIFT_PREFIX = "SPS2."
    private const val REVOCATION_PREFIX = "SPR1."

    /** The gate code: the hash of an event pack the phone must already hold. */
    private const val EVENT_PREFIX = "SPE1."
    private const val EVENT_HASH = 32
    private const val MAX_TEXT = 512

    /** An SPS2 code with every optional part is up to 517 characters. */
    private const val MAX_SHIFT_TEXT = 640
    private const val HAS_REPORT_KEY = 0x01
    private const val HAS_REPORT_SEED = 0x02
    private const val HAS_LEGACY_SEED = 0x04
    /** URL-safe, written without padding; read with or without it. */
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    fun encodeFriend(card: FriendCard): String {
        val nick = card.nickname.truncateUtf8(Limits.NICK_BYTES).utf8()
        val bytes = WireWriter().bytes(card.boxKey).bytes(card.signKey).u8(nick.size).bytes(nick).toByteArray()
        return FRIEND_PREFIX + base64.encode(bytes)
    }

    fun decodeFriend(text: String): FriendCard? {
        val bytes = payload(text, FRIEND_PREFIX) ?: return null
        return parseOrNull {
            val r = WireReader(bytes)
            val boxKey = r.bytes(Crypto.BOX_PUBLIC)
            val signKey = r.bytes(Crypto.SIGN_PUBLIC)
            val nick = r.string(r.u8(), Limits.NICK_BYTES)
            r.expectEnd()
            FriendCard(nick, boxKey, signKey)
        }
    }

    fun encodeStaff(card: StaffCard): String {
        val team = card.teamName.truncateUtf8(Limits.TEAM_BYTES).utf8()
        val zone = card.zone.truncateUtf8(Limits.ZONE_BYTES).utf8()
        val bytes = WireWriter().bytes(card.boxSeed).bytes(card.signSeed)
            .u8(card.role.code)
            .u8(team.size).bytes(team).u8(zone.size).bytes(zone).toByteArray()
        return STAFF_PREFIX + base64.encode(bytes)
    }

    fun decodeStaff(text: String): StaffCard? {
        val bytes = payload(text, STAFF_PREFIX) ?: return null
        return parseOrNull {
            val r = WireReader(bytes)
            val boxSeed = r.bytes(Crypto.SEED)
            val signSeed = r.bytes(Crypto.SEED)
            val code = r.u8()
            val role = StaffRole.entries.firstOrNull { it.code == code } ?: throw MalformedException("role")
            val team = r.string(r.u8(), Limits.TEAM_BYTES)
            val zone = r.string(r.u8(), Limits.ZONE_BYTES)
            r.expectEnd()
            StaffCard(boxSeed, signSeed, role, team, zone)
        }
    }

    fun encodeShift(card: ShiftCard): String {
        require(card.reportSeed == null || card.reportKey != null)
        val zone = card.zone.truncateUtf8(Limits.ZONE_BYTES).utf8()
        val flags = (if (card.reportKey != null) HAS_REPORT_KEY else 0) or
            (if (card.reportSeed != null) HAS_REPORT_SEED else 0) or (if (card.legacyBoxSeed != null) HAS_LEGACY_SEED else 0)
        val w = WireWriter(400).bytes(card.certificate.encode()).bytes(card.keySeed).u8(flags)
        card.reportKey?.let { w.bytes(it.encode()) }
        card.reportSeed?.let { w.bytes(it) }
        card.legacyBoxSeed?.let { w.bytes(it) }
        w.u8(zone.size).bytes(zone)
        return SHIFT_PREFIX + base64.encode(w.toByteArray())
    }

    /** Null also for a certificate of a role a phone can't hold. */
    fun decodeShift(text: String): ShiftCard? {
        val bytes = payload(text, SHIFT_PREFIX, MAX_SHIFT_TEXT) ?: return null
        return parseOrNull {
            val r = WireReader(bytes)
            val cert = Certificate.read(r)
            if (StaffRole.entries.none { it.code == cert.role }) throw MalformedException("role")
            val keySeed = r.bytes(Crypto.SEED)
            val flags = r.u8()
            if (flags and HAS_REPORT_SEED != 0 && flags and HAS_REPORT_KEY == 0) throw MalformedException("report seed")
            val reportKey = if (flags and HAS_REPORT_KEY != 0) Certificate.read(r) else null
            val reportSeed = if (flags and HAS_REPORT_SEED != 0) r.bytes(Crypto.SEED) else null
            val legacy = if (flags and HAS_LEGACY_SEED != 0) r.bytes(Crypto.SEED) else null
            val zone = r.string(r.u8(), Limits.ZONE_BYTES)
            r.expectEnd()
            ShiftCard(cert, keySeed, reportKey, reportSeed, legacy, zone)
        }
    }

    /** SPR1: a revocation announcement the organiser tool prints, for the console to send out. */
    fun encodeRevocation(payload: ByteArray): String = REVOCATION_PREFIX + base64.encode(payload)

    fun decodeRevocation(text: String): ByteArray? = payload(text, REVOCATION_PREFIX)

    fun encodeEventGate(hash: ByteArray): String {
        require(hash.size == EVENT_HASH)
        return EVENT_PREFIX + base64.encode(hash)
    }

    fun decodeEventGate(text: String): ByteArray? = payload(text, EVENT_PREFIX)?.takeIf { it.size == EVENT_HASH }

    fun isStaff(text: String): Boolean = text.startsWith(STAFF_PREFIX) || text.startsWith(SHIFT_PREFIX)

    private fun payload(text: String, prefix: String, maxText: Int = MAX_TEXT): ByteArray? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(prefix) || trimmed.length > maxText) return null
        return try {
            base64.decode(trimmed.substring(prefix.length))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
