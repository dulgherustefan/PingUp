package ro.safetyplease.core.mesh

import kotlinx.coroutines.channels.Channel
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType

/** [position]: one of this phone's shared positions (P2), which relays can't tell from chat; it goes last. */
class Outbound(val packet: Packet, val own: Boolean, val position: Boolean = false)

/**
 * Per-link send queue. Incidents always go before chat; when full, the least important item is dropped first, and
 * positions are the least important of all. [onFull] runs once for every item lost that way, refused or evicted.
 * Not thread-safe: used only from the mesh context.
 */
class SendQueue(private val capacity: Int = 128, private val onFull: () -> Unit = {}) {
    private val levels = Array(LEVELS) { ArrayDeque<Outbound>() }
    private val signal = Channel<Unit>(Channel.CONFLATED)
    var size = 0
        private set

    fun offer(item: Outbound): Boolean {
        val level = if (item.position) POSITION_LEVEL else priorityOf(item.packet.type)
        if (size >= capacity) {
            onFull()
            val victim = (LEVELS - 1 downTo level + 1).firstOrNull { levels[it].isNotEmpty() } ?: return false
            levels[victim].removeLast()
            size--
        }
        levels[level].addLast(item)
        size++
        signal.trySend(Unit)
        return true
    }

    suspend fun awaitItem() {
        while (size == 0) signal.receive()
    }

    /** Takes back this phone's own positions still waiting for [recipients]; returns how many. */
    fun removeOwnPositions(recipients: Set<Long>): Int {
        val queue = levels[POSITION_LEVEL]
        val before = queue.size
        queue.removeAll { it.own && it.position && it.packet.recipient in recipients }
        size -= before - queue.size
        return before - queue.size
    }

    /** Takes out every queued copy of the packet with this dedup key; how many went. */
    fun removeCopies(dedupKey: Long): Int {
        var removed = 0
        for (q in levels) {
            val before = q.size
            q.removeAll { it.packet.dedupKey == dedupKey }
            removed += before - q.size
        }
        size -= removed
        return removed
    }

    /** Highest-priority item right now; call it after the pacing wait, not before. */
    fun poll(): Outbound? {
        for (q in levels) {
            val item = q.removeFirstOrNull()
            if (item != null) {
                size--
                return item
            }
        }
        return null
    }

    companion object {
        private const val LEVELS = 7
        private const val POSITION_LEVEL = LEVELS - 1

        /**
         * SUMMARY and REQUEST carry store-and-forward, so chat load must not push them out. Types this version only
         * relays go last but for positions, except the one reserved for organiser announcements, which goes with reports.
         */
        fun priorityOf(type: Int): Int = when (type) {
            PacketType.HELLO -> 0
            PacketType.INCIDENT_REPORT, PacketType.ANNOUNCEMENT -> 1
            PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL, PacketType.INCIDENT_RESPONSE, PacketType.INCIDENT_UPDATE -> 2
            PacketType.SUMMARY, PacketType.REQUEST, PacketType.SUMMARY2, PacketType.REQUEST2 -> 3
            PacketType.PRIVATE -> 4
            else -> 5
        }
    }
}
