package ro.safetyplease.core.event

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okio.Buffer
import okio.Source
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.KeyPair
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.sha256
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.utf8
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import kotlin.io.encoding.Base64

private const val DAY_S = 86_400L

/** A real event, or an exercise: everything opened under an exercise pack is marked and never sounds a live alarm. */
enum class EventKind(val code: String) { REAL("real"), EXERCISE("exercise") }

enum class PackStatus { VALID, MALFORMED, TAMPERED, WRONG_CHAIN, REVOKED, NOT_YET, EXPIRED }

/**
 * A signed event pack (PROTOCOL.md, section 16): the venue, the first-aid sheets, the information pages and the event's keys, signed by the
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
    /** Static information pages by language, then file name (section 16); empty when the pack has none. */
    val pages: Map<String, Map<String, String>>,
    val hash: ByteArray,
    val notices: NoticeTemplates?,
    /** The grid's box for shared positions (`grid.box`); null when the pack has none and the venue bounds give it. */
    val positionBox: PositionBox?,
    /** Seconds; positions count their minutes from it. `dayStart` in the pack, else the UTC day of [validFrom]. */
    val dayStart: Long,
    /** The sets on the stages, by start; empty when the pack has none. */
    val schedule: List<ScheduleItem> = emptyList(),
    val plan: SitePlan? = null,
) {
    val exercise: Boolean get() = kind == EventKind.EXERCISE

    val hashHex: String get() = hash.toHex()

    /** What a [KeyChain] needs to accept this event's shifts. */
    val issuers: List<Certificate> get() = listOf(organiser, event)

    fun publicKeys(bundled: StaffPublicKeys): StaffPublicKeys = StaffPublicKeys(box ?: bundled.box, sign ?: bundled.sign)
}

/** One set on a stage: [stage] is the id of a venue zone; [start] and [end] are seconds since the Unix epoch. */
data class ScheduleItem(val stage: String, val title: String, val start: Long, val end: Long)

/**
 * The organiser's illustrated site plan (the pack's `plan`), a PNG laid over the real map with its corners on these
 * points; the image's top edge runs from [topLeft] to [topRight].
 */
class SitePlan(val png: ByteArray, val topLeft: GeoPoint, val topRight: GeoPoint, val bottomRight: GeoPoint, val bottomLeft: GeoPoint) {
    val corners: List<GeoPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

class PackCheck(val status: PackStatus, val pack: EventPack? = null)

object EventPacks {
    const val FORMAT = "pingup-event"
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * The site plan's PNG, decoded. In base64 it takes a third more, so a pack with the largest plan stays under
     * [MAX_BYTES], the size every version reads.
     */
    const val PLAN_MAX_BYTES = 2 * 1024 * 1024
    const val SCHEDULE_MAX_ITEMS = 1000
    const val SCHEDULE_TITLE_CHARS = 80

    /** A set longer than this is a typing error. */
    const val SET_MAX_S = 12 * 3600L
    private const val MAX_ID = 64
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val DOMAIN = "SP-PACK-v1".utf8()
    private val json = Json { ignoreUnknownKeys = true }

    fun signedMessage(body: String): ByteArray = DOMAIN + body.utf8()

    /** A pack file as text, or null past [MAX_BYTES]: a file that big isn't a pack. */
    fun read(source: Source): String? {
        val buffer = Buffer()
        while (source.read(buffer, 64 * 1024L) >= 0) {
            if (buffer.size > MAX_BYTES) return null
        }
        return buffer.readUtf8()
    }

    fun hashOf(body: String): ByteArray = sha256(body.utf8())

    /** The pack file of [body], signed by the [organiser]'s key, as tools/gen_staff_keys.py "pack" writes it. */
    fun sign(body: String, crypto: Crypto, organiser: KeyPair): String = JsonObject(
        mapOf(
            "format" to JsonPrimitive(FORMAT),
            "version" to JsonPrimitive(1),
            "body" to JsonPrimitive(body),
            "signature" to JsonPrimitive(crypto.sign(signedMessage(body), organiser.secretKey).toHex()),
        ),
    ).toString()

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
        val site = runCatching { Venue.parse(venue) }.getOrNull()?.takeIf(::zonesHold) ?: return null
        val gridObject = o["grid"] as? JsonObject
        val grid = gridObject?.int("cellMeters") ?: SiteGrid.CELL_METERS
        if (grid !in 10..500) return null
        val positionBox = gridObject?.get("box")?.let { positionBox(it) ?: return null }
        val dayStart = if ("dayStart" in o) o.long("dayStart") ?: return null else null
        val firstAid = (o["firstAid"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: return null }.orEmpty()
        val notices = o["notices"]?.let { NoticeTemplates.parse(it) ?: return null }
        val pages = InfoPages.fromJson(o["pages"]) ?: return null
        val schedule = o["schedule"]?.let { schedule(it, site) ?: return null }.orEmpty()
        val plan = o["plan"]?.let { plan(it) ?: return null }
        return EventPack(
            id, version, kind, o.string("name").orEmpty(), from, until, organiser, event, reportKeys,
            box.takeIf { it.isNotEmpty() }, sign.takeIf { it.isNotEmpty() }, venue, grid, firstAid, pages, hashOf(body), notices,
            positionBox, dayStart ?: (from.floorDiv(DAY_S) * DAY_S), schedule, plan,
        )
    }

    /**
     * What tools/check_venue.py asks of the zones and the app relies on: an id of 1 to [Limits.ZONE_BYTES] bytes, which a
     * report, a notice and a chat message carry, no two alike, and a shape of at least three corners.
     */
    private fun zonesHold(venue: Venue): Boolean {
        val ids = venue.zones.map { it.id }
        return ids.all { it.isNotEmpty() && it.utf8().size <= Limits.ZONE_BYTES } && ids.toSet().size == ids.size &&
            venue.zones.all { it.polygon.size >= 3 }
    }

    /**
     * `[{"stage", "title", "start", "end"}]`, sorted by start: each stage a zone of [venue], a title of at most
     * [SCHEDULE_TITLE_CHARS], a set of at most [SET_MAX_S]; null when one isn't, or past [SCHEDULE_MAX_ITEMS].
     */
    private fun schedule(element: JsonElement, venue: Venue): List<ScheduleItem>? {
        val list = element as? JsonArray ?: return null
        if (list.size > SCHEDULE_MAX_ITEMS) return null
        return list.map { entry ->
            val o = entry as? JsonObject ?: return null
            val stage = o.string("stage")?.takeIf { venue.zone(it) != null } ?: return null
            val title = o.string("title")?.takeIf { it.isNotBlank() && it.length <= SCHEDULE_TITLE_CHARS } ?: return null
            val start = o.long("start") ?: return null
            val end = o.long("end")?.takeIf { it > start && it - start <= SET_MAX_S } ?: return null
            ScheduleItem(stage, title, start, end)
        }.sortedBy { it.start }
    }

    /** `{"png": base64, "topLeft", "topRight", "bottomRight", "bottomLeft": {"lat", "lon"}}`, a PNG of at most [PLAN_MAX_BYTES]. */
    private fun plan(element: JsonElement): SitePlan? {
        val o = element as? JsonObject ?: return null
        val text = o.string("png")?.takeIf { it.length <= (PLAN_MAX_BYTES + 2) / 3 * 4 } ?: return null
        val png = try {
            Base64.Default.decode(text)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (png.size > PLAN_MAX_BYTES || png.size < PNG_SIGNATURE.size || !png.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) return null
        fun corner(key: String): GeoPoint? {
            val c = o[key] as? JsonObject ?: return null
            val lat = c.double("lat")?.takeIf { it in -90.0..90.0 } ?: return null
            val lon = c.double("lon")?.takeIf { it in -180.0..180.0 } ?: return null
            return GeoPoint(lat, lon)
        }
        val corners = listOf("topLeft", "topRight", "bottomRight", "bottomLeft").map { corner(it) ?: return null }
        if (corners.toSet().size != corners.size) return null
        return SitePlan(png, corners[0], corners[1], corners[2], corners[3])
    }

    /** `{"originLat", "originLon", "azimuth", "version"}`; azimuth and version may be left out. */
    private fun positionBox(element: JsonElement): PositionBox? {
        val o = element as? JsonObject ?: return null
        val lat = o.double("originLat")?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = o.double("originLon")?.takeIf { it in -180.0..180.0 } ?: return null
        val azimuth = if ("azimuth" in o) o.double("azimuth")?.takeIf { it >= 0.0 && it < 360.0 } ?: return null else 0.0
        val version = if ("version" in o) o.int("version")?.takeIf { it >= 0 } ?: return null else 0
        return PositionBox(lat, lon, azimuth, version)
    }

    private fun JsonObject.double(key: String): Double? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

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
