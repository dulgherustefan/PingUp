package ro.safetyplease.app.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest

object PacketType {
    const val HELLO = 0x01
    const val SUMMARY = 0x02
    const val REQUEST = 0x03
    const val INCIDENT_REPORT = 0x10
    const val INCIDENT_ACK = 0x11
    const val INCIDENT_CANCEL = 0x12
    const val PRIVATE = 0x20
    const val TEST = 0x7F

    fun name(type: Int): String = when (type) {
        HELLO -> "HELLO"
        SUMMARY -> "SUMMARY"
        REQUEST -> "REQUEST"
        INCIDENT_REPORT -> "INCIDENT"
        INCIDENT_ACK -> "ACK"
        INCIDENT_CANCEL -> "CANCEL"
        PRIVATE -> "PRIVATE"
        TEST -> "TEST"
        else -> "0x%02x".format(type)
    }

    /** Pachete care nu parasesc niciodata legatura pe care au fost trimise. */
    fun isLinkLocal(type: Int): Boolean = type == HELLO || type == SUMMARY || type == REQUEST
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
     * Numarul de legaturi traversate pana aici. Originea trimite cu ttl 7, deci un vecin direct
     * primeste ttl 7 si vede 1 hop; lantul A-B-C-D da 3 la D.
     */
    val hops: Int get() = PacketCodec.MAX_TTL - ttl + 1

    /**
     * Cheia de dedup acopera tot ce nu se schimba pe drum, nu doar [id]. Altfel un nod rau-voitor
     * ar putea opri un pachet trimitand inaintea lui gunoi cu acelasi id.
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

/** Un cadru de pe fir: fie un pachet intreg, fie o bucata dintr-unul. */
class Frame(val packet: Packet, val fragment: FragmentInfo?)

object PacketCodec {
    const val VERSION = 1
    const val HEADER_SIZE = 26
    const val RECIPIENT_SIZE = 8
    const val FRAGMENT_SIZE = 4
    const val MAX_PAYLOAD = 480
    const val MAX_TTL = 7

    /** Sub atat nu incape antetul complet plus o bucata utila de payload. */
    const val MIN_FRAME = 64

    const val FLAG_RECIPIENT = 0x01
    const val FLAG_FRAGMENT = 0x02
    const val FLAG_ENCRYPTED = 0x04
    private const val KNOWN_FLAGS = FLAG_RECIPIENT or FLAG_FRAGMENT or FLAG_ENCRYPTED

    private val knownTypes = setOf(
        PacketType.HELLO, PacketType.SUMMARY, PacketType.REQUEST,
        PacketType.INCIDENT_REPORT, PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL,
        PacketType.PRIVATE, PacketType.TEST,
    )

    fun encodedSize(p: Packet): Int =
        HEADER_SIZE + (if (p.recipient != null) RECIPIENT_SIZE else 0) + p.payload.size

    fun encode(p: Packet): ByteArray = write(p, null, p.payload, 0, p.payload.size)

    /**
     * Imparte pachetul in cadre de cel mult [maxFrame] octeti. Fragmentarea e per legatura:
     * fiecare hop reasambleaza si refragmenteaza dupa MTU-ul legaturii urmatoare.
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

    /** Intoarce null pentru orice cadru care nu respecta formatul; nimic invalid nu trece mai sus. */
    fun decode(bytes: ByteArray): Frame? = parseOrNull {
        if (bytes.size < HEADER_SIZE) throw MalformedException("short header")
        val r = WireReader(bytes)
        if (r.u8() != VERSION) throw MalformedException("version")
        val type = r.u8()
        if (type !in knownTypes) throw MalformedException("type")
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
}
