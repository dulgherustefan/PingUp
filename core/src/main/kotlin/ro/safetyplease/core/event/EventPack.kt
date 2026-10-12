package ro.safetyplease.core.event

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.utf8
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A real event, or an exercise: everything opened under an exercise pack is marked and never sounds a live alarm. */
enum class EventKind(val code: String) { REAL("real"), EXERCISE("exercise") }

enum class PackStatus { VALID, MALFORMED, TAMPERED, WRONG_CHAIN, REVOKED, NOT_YET, EXPIRED }

/**
 * A signed event pack (PROTOCOL.md, section 16): the venue, the first-aid sheets and the event's keys, signed by the
 * organiser whose certificate the Ping Up root signed. [box] and [sign] replace the build's shared keys; an exercise
 * pack always carries its own. [hash] is SHA-256 over the signed body, what the gate QR names. [notices] are the texts
 * the console's notices name; null without them, when the app's own are used.
 */
class EventPack(
    val id: String,
    val version: Int,
    val kind: EventKind,
    val name: String,
    val validFrom: Long,
    val validUntil: Long,
    val organiser: Certificate,
    val event: Certificate,
    val reportKeys: List<Certificate>,
    val box: ByteArray?,
    val sign: ByteArray?,
    val venue: String,
    val gridMeters: Int,
    val firstAid: Map<String, String>,
    val hash: ByteArray,
    val notices: NoticeTemplates? = null,
) {
    val exercise: Boolean get() = kind == EventKind.EXERCISE

    val hashHex: String get() = hash.toHex()

    /** What a [KeyChain] needs to accept this event's shifts. */
    val issuers: List<Certificate> get() = listOf(organiser, event)

    fun publicKeys(bundled: StaffPublicKeys): StaffPublicKeys = StaffPublicKeys(box ?: bundled.box, sign ?: bundled.sign)
}

class PackCheck(val status: PackStatus, val pack: EventPack? = null)

object EventPacks {
    const val FORMAT = "pingup-event"
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_ID = 64
    private val DOMAIN = "SP-PACK-v1".utf8()
    private val json = Json { ignoreUnknownKeys = true }

    fun signedMessage(body: String): ByteArray = DOMAIN + body.utf8()

    /** A pack file as text, or null past [MAX_BYTES]: a file that big isn't a pack. */
    fun read(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    fun hashOf(body: String): ByteArray = sha256(body.utf8())

    /**
     * Whether [text] is a pack this phone may use at [now] (seconds): the root signed the organiser, the organiser
     * signed the pack and the event, the event signed every report key, and now is inside the pack's window and the
     * event's, give or take [KeyChain.SKEW_S]. A pack is loaded before the event, so the event's certificate need
     * not hold yet. A pack whose organiser or event key is in [revoked] is REVOKED; a revoked report key is only left out.
     */
    fun check(text: String, crypto: Crypto, root: ByteArray?, now: Long, revoked: Collection<Long> = emptySet()): PackCheck {
        if (text.length > MAX_BYTES) return PackCheck(PackStatus.MALFORMED)
        val (body, signature) = outer(text) ?: return PackCheck(PackStatus.MALFORMED)
        val pack = parse(body) ?: return PackCheck(PackStatus.MALFORMED)
        if (root == null) return PackCheck(PackStatus.WRONG_CHAIN)
        val chain = KeyChain(crypto, Fixed(now), pack.sign ?: ByteArray(Certificate.KEY_SIZE), root, pack.issuers, pack.reportKeys)
        chain.revokeIds(revoked)
        when (chain.status(pack.organiser, now)) {
            CertStatus.VALID -> Unit
            CertStatus.REVOKED -> return PackCheck(PackStatus.REVOKED)
            CertStatus.OUTSIDE_WINDOW -> return PackCheck(if (now < pack.organiser.validFrom) PackStatus.NOT_YET else PackStatus.EXPIRED)
            else -> return PackCheck(PackStatus.WRONG_CHAIN)
        }
        if (!crypto.verify(signature, signedMessage(body), pack.organiser.key)) return PackCheck(PackStatus.TAMPERED)
        // each key is checked at the moment of its window nearest to now; days already over don't matter any more
        fun holds(cert: Certificate) = chain.status(cert, now.coerceIn(cert.validFrom, cert.validUntil)) == CertStatus.VALID
        val event = pack.event
        if (event.id in revoked) return PackCheck(PackStatus.REVOKED)
        if (event.role != CertRole.EVENT || !holds(event)) return PackCheck(PackStatus.WRONG_CHAIN)
        val current = pack.reportKeys.filter { it.validUntil + KeyChain.SKEW_S >= now && it.id !in revoked }
        if (current.any { it.role != CertRole.REPORT_KEY || !holds(it) }) return PackCheck(PackStatus.WRONG_CHAIN)
        if (now < pack.validFrom - KeyChain.SKEW_S) return PackCheck(PackStatus.NOT_YET)
        if (now > minOf(pack.validUntil, event.validUntil) + KeyChain.SKEW_S) return PackCheck(PackStatus.EXPIRED)
        return PackCheck(PackStatus.VALID, pack)
    }

    private fun outer(text: String): Pair<String, ByteArray>? {
        val root = element(text) as? JsonObject ?: return null
        if (root.string("format") != FORMAT || root.int("version") != 1) return null
        val body = root.string("body") ?: return null
        val signature = root.hex("signature")?.takeIf { it.size == Crypto.SIGNATURE } ?: return null
        return body to signature
    }

    private fun parse(body: String): EventPack? {
        val o = element(body) as? JsonObject ?: return null
        val id = o.string("id")?.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
        val version = o.int("version")?.takeIf { it >= 1 } ?: return null
        val kind = EventKind.entries.firstOrNull { it.code == o.string("kind") } ?: return null
        val from = o.long("validFrom") ?: return null
        val until = o.long("validUntil")?.takeIf { it > from } ?: return null
        val organiser = o.hex("organiser")?.let(Certificate::decode) ?: return null
        val event = o.hex("event")?.let(Certificate::decode) ?: return null
        val reportKeys = (o["reportKeys"] as? JsonArray)?.map {
            ((it as? JsonPrimitive)?.contentOrNull?.let(::hexOrNull)?.let(Certificate::decode)) ?: return null
        }.orEmpty()
        val box = o.key("box") ?: return null
        val sign = o.key("sign") ?: return null
        if (kind == EventKind.EXERCISE && (box.isEmpty() || sign.isEmpty())) return null
        val venue = (o["venue"] as? JsonObject)?.toString() ?: return null
        if (runCatching { Venue.parse(venue) }.isFailure) return null
        val grid = (o["grid"] as? JsonObject)?.int("cellMeters") ?: SiteGrid.CELL_METERS
        if (grid !in 10..500) return null
        val firstAid = (o["firstAid"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: return null }.orEmpty()
        val notices = o["notices"]?.let { NoticeTemplates.parse(it) ?: return null }
        return EventPack(
            id, version, kind, o.string("name").orEmpty(), from, until, organiser, event, reportKeys,
            box.takeIf { it.isNotEmpty() }, sign.takeIf { it.isNotEmpty() }, venue, grid, firstAid, hashOf(body), notices,
        )
    }

    private fun element(text: String): JsonElement? = try {
        json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun hexOrNull(text: String): ByteArray? = runCatching { text.hexToBytes() }.getOrNull()

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

    private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    private fun JsonObject.hex(key: String): ByteArray? = string(key)?.let(::hexOrNull)

    /** An optional 32-byte public key: empty when absent, null when present but wrong. */
    private fun JsonObject.key(key: String): ByteArray? {
        if (key !in this) return ByteArray(0)
        return hex(key)?.takeIf { it.size == Certificate.KEY_SIZE }
    }

    private class Fixed(private val sec: Long) : Clock {
        override fun wallMs(): Long = sec * 1000

        override fun monoMs(): Long = sec * 1000
    }
}
