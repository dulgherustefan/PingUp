package ro.safetyplease.core.mesh

import kotlinx.coroutines.channels.Channel
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType

class Outbound(val packet: Packet, val own: Boolean)

/**
 * Per-link send queue. Incidents always go before chat; when full, the least important item is dropped first.
 * Not thread-safe: used only from the mesh context.
 */
class SendQueue(private val capacity: Int = 128) {
    private val levels = Array(LEVELS) { ArrayDeque<Outbound>() }
    private val signal = Channel<Unit>(Channel.CONFLATED)
    var size = 0
        private set

    fun offer(item: Outbound): Boolean {
        val level = priorityOf(item.packet.type)
        if (size >= capacity) {
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
        private const val LEVELS = 5

        fun priorityOf(type: Int): Int = when (type) {
            PacketType.HELLO -> 0
            PacketType.INCIDENT_REPORT -> 1
            PacketType.INCIDENT_ACK, PacketType.INCIDENT_CANCEL -> 2
            PacketType.PRIVATE -> 3
            else -> 4
        }
    }
}
