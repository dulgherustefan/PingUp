package ro.safetyplease.core.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest

object PacketType {
    const val HELLO = 0x01
    const val SUMMARY = 0x02
    const val REQUEST = 0x03
    const val SUMMARY2 = 0x04
    const val REQUEST2 = 0x05
    const val INCIDENT_REPORT = 0x10
    const val INCIDENT_ACK = 0x11
    const val INCIDENT_CANCEL = 0x12
    const val PRIVATE = 0x20

    /** Reserved for signed organiser announcements. Nothing sends it yet; 1.1 relays it like an unknown type. */
    const val ANNOUNCEMENT = 0x30
    const val TEST = 0x7F

    /** The types 1.0 reads. It counts any other as malformed, so a 1.1 node never sends one to a 1.0 peer. */
    val V1_0 = setOf(HELLO, SUMMARY, REQUEST, INCIDENT_REPORT, INCIDENT_ACK, INCIDENT_CANCEL, PRIVATE, TEST)

    private val known = V1_0 + setOf(SUMMARY2, REQUEST2)

    /**
     * Chat reaches 4 hops, and each relay passes it on only a few links ([ro.safetyplease.core.mesh.FanOut]): a chat
     * message no longer costs a flood of the whole crowd. Friends farther apart get it once they walk closer.
     */
    const val CHAT_TTL = 4

    /** Types this version doesn't read, the reserved one included, start at this TTL and are never relayed above it. */
    const val OPAQUE_MAX_TTL = 3
    const val OPAQUE_MAX_PAYLOAD = 256

    fun name(type: Int): String = when (type) {
        HELLO -> "HELLO"
        SUMMARY -> "SUMMARY"
        REQUEST -> "REQUEST"
        SUMMARY2 -> "SUMMARY2"
        REQUEST2 -> "REQUEST2"
        INCIDENT_REPORT -> "INCIDENT"
        INCIDENT_ACK -> "ACK"
        INCIDENT_CANCEL -> "CANCEL"
        PRIVATE -> "PRIVATE"
        ANNOUNCEMENT -> "ANNOUNCE"
        TEST -> "TEST"
        else -> "0x%02x".format(type)
    }

    /** Types this version reads; the others are relayed opaquely. */
    fun isKnown(type: Int): Boolean = type in known

    /** 0x00 to 0x0F never leave the link they were sent on, including the ones this version doesn't know. */
    fun isLinkLocal(type: Int): Boolean = type and 0xF0 == 0

    /** The TTL a packet starts with (PROTOCOL.md, section 5). Incidents keep the 7 every flooded type had in 1.0. */
    fun originTtl(type: Int): Int = when (type) {
        HELLO, SUMMARY, REQUEST, SUMMARY2, REQUEST2 -> 1
        INCIDENT_REPORT, INCIDENT_ACK, INCIDENT_CANCEL -> PacketCodec.MAX_TTL
        PRIVATE -> CHAT_TTL
        ANNOUNCEMENT -> OPAQUE_MAX_TTL
        TEST -> PacketCodec.MAX_TTL
        else -> if (isLinkLocal(type)) 1 else OPAQUE_MAX_TTL
    }

    /** Announcements will carry up to about 400 B, so the reserved type gets the whole payload. */
    fun opaqueMaxPayload(type: Int): Int = if (type == ANNOUNCEMENT) PacketCodec.MAX_PAYLOAD else OPAQUE_MAX_PAYLOAD
}

class Packet(
    val type: Int,
    val ttl: Int,
    val id: Long,
    val sender: Long,
    val timestamp: Long,
    val recipient: Long? = null,
    val encrypted: Boolean = false,
    val payload: ByteArray,
) {
    fun withTtl(newTtl: Int) = Packet(type, newTtl, id, sender, timestamp, recipient, encrypted, payload)

    /**
     * Links traversed so far: a direct neighbor sees 1, the chain A-B-C-D gives 3 at D. Counted from the type's
     * [PacketType.originTtl], or from 7 when the TTL is higher than that, as from a 1.0 sender, which started every
     * type at 7.
     */
    val hops: Int
        get() {
            val start = PacketType.originTtl(type).let { if (ttl > it) PacketCodec.MAX_TTL else it }
            return start - ttl + 1
        }

    /**
     * The dedup key covers everything that doesn't change in transit, not just [id]. Otherwise a malicious node
     * could block a packet by sending garbage with the same id ahead of it.
     */
    val dedupKey: Long by lazy(LazyThreadSafetyMode.NONE) {
        val md = MessageDigest.getInstance("SHA-256")
        val head = ByteBuffer.allocate(38)
            .put(type.toByte()).put((if (encrypted) 1 else 0).toByte())
            .putLong(id).putLong(sender).putInt(timestamp.toInt())
            .putLong(recipient ?: 0L).putLong(if (recipient != null) 1L else 0L)
        md.update(head.array())
        md.update(payload)
        ByteBuffer.wrap(md.digest()).long
    }
}

class FragmentInfo(val fragId: Int, val index: Int, val count: Int)

/** A frame on the wire: a whole packet or a fragment of one. */
class Frame(val packet: Packet, val fragment: FragmentInfo?)

object PacketCodec {
    /** Header version. 1.1 keeps the 1.0 header and announces itself with [NodeFlags.PROTOCOL_1_1]. */
    const val VERSION = 1
    const val HEADER_SIZE = 26
    const val RECIPIENT_SIZE = 8
    const val FRAGMENT_SIZE = 4
    const val MAX_PAYLOAD = 480
    const val MAX_TTL = 7

    /** Below this the full header plus a useful payload chunk doesn't fit. */
    const val MIN_FRAME = 64

    const val FLAG_RECIPIENT = 0x01
    const val FLAG_FRAGMENT = 0x02
    const val FLAG_ENCRYPTED = 0x04
    private const val KNOWN_FLAGS = FLAG_RECIPIENT or FLAG_FRAGMENT or FLAG_ENCRYPTED

    fun encodedSize(p: Packet): Int =
        HEADER_SIZE + (if (p.recipient != null) RECIPIENT_SIZE else 0) + p.payload.size

    fun encode(p: Packet): ByteArray = write(p, null, p.payload, 0, p.payload.size)

    /**
     * Splits the packet into frames of at most [maxFrame] bytes. Fragmentation is per link:
     * each hop reassembles and re-fragments for the next link's MTU.
     */
    fun toFrames(p: Packet, maxFrame: Int, fragId: Int): List<ByteArray> {
        require(maxFrame >= MIN_FRAME) { "frame too small" }
        require(p.payload.size <= MAX_PAYLOAD) { "payload too large" }
        if (encodedSize(p) <= maxFrame) return listOf(encode(p))
        val overhead = HEADER_SIZE + (if (p.recipient != null) RECIPIENT_SIZE else 0) + FRAGMENT_SIZE
        val chunk = maxFrame - overhead
        val count = (p.payload.size + chunk - 1) / chunk
        return List(count) { i ->
            val from = i * chunk
            val len = minOf(chunk, p.payload.size - from)
            write(p, FragmentInfo(fragId and 0xffff, i, count), p.payload, from, len)
        }
    }

    private fun write(p: Packet, frag: FragmentInfo?, payload: ByteArray, from: Int, len: Int): ByteArray {
        var flags = 0
        if (p.recipient != null) flags = flags or FLAG_RECIPIENT
        if (frag != null) flags = flags or FLAG_FRAGMENT
        if (p.encrypted) flags = flags or FLAG_ENCRYPTED
        val w = WireWriter(HEADER_SIZE + RECIPIENT_SIZE + FRAGMENT_SIZE + len)
        w.u8(VERSION).u8(p.type).u8(p.ttl).u8(flags)
        w.i64(p.id).i64(p.sender).u32(p.timestamp).u16(len)
        if (p.recipient != null) w.i64(p.recipient)
        if (frag != null) w.u16(frag.fragId).u8(frag.index).u8(frag.count)
        w.bytes(payload.copyOfRange(from, from + len))
        return w.toByteArray()
    }

    /**
     * Returns null for any frame that breaks the format; nothing invalid gets further. Any type decodes: what to do
     * with one this version doesn't read is up to the receiver.
     */
    fun decode(bytes: ByteArray): Frame? = parseOrNull {
        if (bytes.size < HEADER_SIZE) throw MalformedException("short header")
        val r = WireReader(bytes)
        if (r.u8() != VERSION) throw MalformedException("version")
        val type = r.u8()
        val ttl = r.u8()
        if (ttl < 1 || ttl > MAX_TTL) throw MalformedException("ttl")
        val flags = r.u8()
        if (flags and KNOWN_FLAGS.inv() != 0) throw MalformedException("flags")
        val id = r.i64()
        val sender = r.i64()
        val timestamp = r.u32()
        val length = r.u16()
        if (length > MAX_PAYLOAD) throw MalformedException("length")
        val recipient = if (flags and FLAG_RECIPIENT != 0) r.i64() else null
        val frag = if (flags and FLAG_FRAGMENT != 0) {
            val fragId = r.u16()
            val index = r.u8()
            val count = r.u8()
            if (count < 2 || index >= count || length == 0) throw MalformedException("fragment")
            FragmentInfo(fragId, index, count)
        } else null
        if (r.remaining != length) throw MalformedException("length mismatch")
        val payload = r.bytes(length)
        Frame(Packet(type, ttl, id, sender, timestamp, recipient, flags and FLAG_ENCRYPTED != 0, payload), frag)
    }

    /**
     * A frame a newer version may have written: another header version, or a header flag this one doesn't know.
     * Its layout can't be trusted, so it is dropped, but it isn't malformed.
     */
    fun isForeign(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes[0].toInt() and 0xff != VERSION) return true
        return bytes.size >= 4 && bytes[3].toInt() and 0xff and KNOWN_FLAGS.inv() != 0
    }
}
