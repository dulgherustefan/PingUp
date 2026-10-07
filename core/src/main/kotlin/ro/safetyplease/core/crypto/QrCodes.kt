package ro.safetyplease.core.crypto

import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.MalformedException
import ro.safetyplease.core.protocol.WireReader
import ro.safetyplease.core.protocol.WireWriter
import ro.safetyplease.core.protocol.parseOrNull
import ro.safetyplease.core.util.truncateUtf8
import ro.safetyplease.core.util.utf8
import java.util.Base64

class FriendCard(val nickname: String, val boxKey: ByteArray, val signKey: ByteArray) {
    val nodeId: Long get() = Identity.nodeIdOf(signKey, boxKey)
}

enum class StaffRole { STAFF, ANCHOR }

/** Continutul QR-ului de staff: seminte din care se deriva cheile, rolul si echipa sau zona. */
class StaffCard(
    val boxSeed: ByteArray,
    val signSeed: ByteArray,
    val role: StaffRole,
    val teamName: String,
    val zone: String,
)

/** Formatele QR, versionate prin prefix. Acelasi format e produs de tools/gen_staff_keys.py. */
object QrCodes {
    private const val FRIEND_PREFIX = "SPF1."
    private const val STAFF_PREFIX = "SPS1."
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encodeFriend(card: FriendCard): String {
        val nick = card.nickname.truncateUtf8(Limits.NICK_BYTES).utf8()
        val bytes = WireWriter().bytes(card.boxKey).bytes(card.signKey).u8(nick.size).bytes(nick).toByteArray()
        return FRIEND_PREFIX + encoder.encodeToString(bytes)
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
            .u8(if (card.role == StaffRole.ANCHOR) 2 else 1)
            .u8(team.size).bytes(team).u8(zone.size).bytes(zone).toByteArray()
        return STAFF_PREFIX + encoder.encodeToString(bytes)
    }

    fun decodeStaff(text: String): StaffCard? {
        val bytes = payload(text, STAFF_PREFIX) ?: return null
        return parseOrNull {
            val r = WireReader(bytes)
            val boxSeed = r.bytes(Crypto.SEED)
            val signSeed = r.bytes(Crypto.SEED)
            val role = when (r.u8()) {
                1 -> StaffRole.STAFF
                2 -> StaffRole.ANCHOR
                else -> throw MalformedException("role")
            }
            val team = r.string(r.u8(), Limits.TEAM_BYTES)
            val zone = r.string(r.u8(), Limits.ZONE_BYTES)
            r.expectEnd()
            StaffCard(boxSeed, signSeed, role, team, zone)
        }
    }

    fun isStaff(text: String): Boolean = text.startsWith(STAFF_PREFIX)

    private fun payload(text: String, prefix: String): ByteArray? {
        val trimmed = text.trim()
        if (!trimmed.startsWith(prefix) || trimmed.length > 512) return null
        return try {
            decoder.decode(trimmed.substring(prefix.length))
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
