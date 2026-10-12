package ro.safetyplease.core.protocol

/**
 * Protocol 1.0 as it shipped (main at d44d042), frozen so that changes to the current codecs can't hide a break.
 * Whatever its decoders reject, a 1.0 phone counts as malformed, and five in a minute get the sender banned. Its
 * encoders write what a 1.0 phone sends; GoldenVectorsTest pins them to the 1.0 vectors.
 */
object Frozen10 {
    private val types = setOf(0x01, 0x02, 0x03, 0x10, 0x11, 0x12, 0x20, 0x7F)

    fun encodeFrames(p: Packet, maxFrame: Int, fragId: Int): List<ByteArray> {
        val head = 26 + (if (p.recipient != null) 8 else 0)
        if (head + p.payload.size <= maxFrame) return listOf(writeFrame(p, null, p.payload))
        val chunk = maxFrame - head - 4
        val count = (p.payload.size + chunk - 1) / chunk
        return List(count) { i ->
            val part = p.payload.copyOfRange(i * chunk, minOf((i + 1) * chunk, p.payload.size))
            writeFrame(p, FragmentInfo(fragId and 0xffff, i, count), part)
        }
    }

    private fun writeFrame(p: Packet, frag: FragmentInfo?, part: ByteArray): ByteArray {
        val flags = (if (p.recipient != null) 0x01 else 0) or (if (frag != null) 0x02 else 0) or
            (if (p.encrypted) 0x04 else 0)
        val w = WireWriter().u8(1).u8(p.type).u8(p.ttl).u8(flags)
            .i64(p.id).i64(p.sender).u32(p.timestamp).u16(part.size)
        if (p.recipient != null) w.i64(p.recipient)
        if (frag != null) w.u16(frag.fragId).u8(frag.index).u8(frag.count)
        return w.bytes(part).toByteArray()
    }

    fun encodeHello(nodeId: Long, flags: Int, nickname: String?): ByteArray {
        val nick = nickname?.toByteArray() ?: ByteArray(0)
        return WireWriter().u8(1).i64(nodeId).u8(flags).u8(nick.size).bytes(nick).toByteArray()
    }

    fun encodeSummary(entries: List<SummaryEntry>): ByteArray {
        val w = WireWriter().u8(entries.size)
        for (e in entries) w.i64(e.id8).u8((if (e.hasReport) 0x80 else 0) or e.ackStatus)
        return w.toByteArray()
    }

    fun encodeRequest(entries: List<RequestEntry>): ByteArray {
        val w = WireWriter().u8(entries.size)
        for (e in entries) w.i64(e.id8).u8((if (e.wantReport) 0x80 else 0) or (if (e.wantAck) 0x01 else 0))
        return w.toByteArray()
    }

    /** A signed ACK as 1.0 sends it: [IncidentAck.extensions] didn't exist. */
    fun encodeAck(ack: IncidentAck): ByteArray {
        val team = ack.teamName.toByteArray()
        return WireWriter().bytes(ack.incidentId).u8(ack.status).u32(ack.timestamp).u8(team.size).bytes(team)
            .bytes(ack.signature).toByteArray()
    }

    fun frame(bytes: ByteArray): Frame? = parseOrNull {
        if (bytes.size < 26) throw MalformedException("short header")
        val r = WireReader(bytes)
        if (r.u8() != 1) throw MalformedException("version")
        val type = r.u8()
        if (type !in types) throw MalformedException("type")
        val ttl = r.u8()
        if (ttl < 1 || ttl > 7) throw MalformedException("ttl")
        val flags = r.u8()
        if (flags and 0x07.inv() != 0) throw MalformedException("flags")
        val id = r.i64()
        val sender = r.i64()
        val timestamp = r.u32()
        val length = r.u16()
        if (length > 480) throw MalformedException("length")
        val recipient = if (flags and 0x01 != 0) r.i64() else null
        val frag = if (flags and 0x02 != 0) {
            val fragId = r.u16()
            val index = r.u8()
            val count = r.u8()
            if (count < 2 || index >= count || length == 0) throw MalformedException("fragment")
            FragmentInfo(fragId, index, count)
        } else null
        if (r.remaining != length) throw MalformedException("length mismatch")
        Frame(Packet(type, ttl, id, sender, timestamp, recipient, flags and 0x04 != 0, r.bytes(length)), frag)
    }

    /** Includes the engine's check that the version is 1. */
    fun hello(payload: ByteArray): Hello? = parseOrNull {
        val r = WireReader(payload)
        val version = r.u8()
        val nodeId = r.i64()
        val flags = r.u8()
        val nick = r.string(r.u8(), 24)
        r.expectEnd()
        if (nodeId == 0L || version != 1) throw MalformedException("hello")
        Hello(version, nodeId, flags, nick.ifEmpty { null })
    }

    fun summary(payload: ByteArray): List<SummaryEntry>? = parseOrNull {
        val r = WireReader(payload)
        val count = r.u8()
        if (count > 50) throw MalformedException("summary too long")
        val out = List(count) {
            val id8 = r.i64()
            val state = r.u8()
            if (state and 0x7f > 4) throw MalformedException("ack status")
            SummaryEntry(id8, state and 0x80 != 0, state and 0x7f)
        }
        r.expectEnd()
        out
    }

    fun request(payload: ByteArray): List<RequestEntry>? = parseOrNull {
        val r = WireReader(payload)
        val count = r.u8()
        if (count > 50) throw MalformedException("request too long")
        val out = List(count) {
            val id8 = r.i64()
            val want = r.u8()
            if (want and 0x81.inv() != 0) throw MalformedException("want bits")
            RequestEntry(id8, want and 0x80 != 0, want and 0x01 != 0)
        }
        r.expectEnd()
        out
    }

    fun ack(payload: ByteArray): IncidentAck? = parseOrNull {
        val r = WireReader(payload)
        val id = r.bytes(16)
        val status = r.u8()
        if (status < 1 || status > 4) throw MalformedException("status")
        val timestamp = r.u32()
        val team = r.string(r.u8(), 24)
        val signature = r.bytes(64)
        r.expectEnd()
        IncidentAck(id, status, timestamp, team, signature)
    }

    fun body(plain: ByteArray): IncidentBody? = parseOrNull {
        val r = WireReader(plain)
        val category = r.u8()
        if (category !in 1..7) throw MalformedException("category")
        val severity = r.u8()
        if (severity !in 1..3) throw MalformedException("severity")
        val timestamp = r.u32()
        val flags = r.u8()
        if (flags and 0x07.inv() != 0) throw MalformedException("flags")
        val zone = r.string(r.u8(), 32)
        val lat = if (flags and 0x01 != 0) r.i32() / 1e6 else null
        val lon = if (flags and 0x01 != 0) r.i32() / 1e6 else null
        val description = r.string(r.u16(), 300)
        val nick = if (flags and 0x02 != 0) r.string(r.u8(), 24) else null
        val cancelHash = if (flags and 0x04 != 0) r.bytes(16) else null
        r.expectEnd()
        IncidentBody(category, severity, timestamp, zone, lat, lon, description, nick, cancelHash)
    }

    /** What a 1.0 relay checks before it stores or relays a packet; the ACK signature is checked apart. */
    fun payloadOk(packet: Packet): Boolean = when (packet.type) {
        0x10 -> packet.payload.size > 64
        0x11 -> ack(packet.payload) != null
        0x12 -> packet.payload.size == 32
        0x20 -> packet.recipient != null && packet.sender != 0L && packet.encrypted
        else -> true
    }
}
