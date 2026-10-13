package ro.safetyplease.core.protocol

/** Reassembly for a single link. State is bounded: at most [maxSets] packets in progress. */
class Reassembler(
    private val maxSets: Int = 8,
    private val timeoutMs: Long = 30_000,
) {
    sealed interface Result {
        class Complete(val packet: Packet) : Result
        data object Pending : Result
        data object Invalid : Result
    }

    private class PartialSet(val first: Packet, val count: Int, val startedAt: Long) {
        val parts = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private val sets = LinkedHashMap<Int, PartialSet>()

    val pendingSets: Int get() = sets.size

    fun accept(frame: Frame, nowMs: Long): Result {
        val frag = frame.fragment ?: return Result.Complete(frame.packet)
        purge(nowMs)
        val p = frame.packet
        var set = sets[frag.fragId]
        if (set == null) {
            if (sets.size >= maxSets) sets.remove(sets.keys.first())
            set = PartialSet(p, frag.count, nowMs)
            sets[frag.fragId] = set
        } else if (set.count != frag.count || !sameHeader(set.first, p)) {
            sets.remove(frag.fragId)
            return Result.Invalid
        }
        if (set.parts[frag.index] != null) return Result.Pending
        set.parts[frag.index] = p.payload
        set.received++
        set.bytes += p.payload.size
        if (set.bytes > PacketCodec.MAX_PAYLOAD) {
            sets.remove(frag.fragId)
            return Result.Invalid
        }
        if (set.received < set.count) return Result.Pending
        sets.remove(frag.fragId)
        val payload = ByteArray(set.bytes)
        var offset = 0
        for (part in set.parts) {
            part!!.copyInto(payload, offset)
            offset += part.size
        }
        val f = set.first
        return Result.Complete(Packet(f.type, f.ttl, f.id, f.sender, f.timestamp, f.recipient, f.encrypted, payload))
    }

    private fun sameHeader(a: Packet, b: Packet): Boolean =
        a.id == b.id && a.type == b.type && a.ttl == b.ttl && a.sender == b.sender &&
            a.timestamp == b.timestamp && a.recipient == b.recipient && a.encrypted == b.encrypted

    private fun purge(nowMs: Long) {
        val it = sets.values.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().startedAt > timeoutMs) it.remove()
        }
    }
}
