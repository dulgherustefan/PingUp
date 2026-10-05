package ro.safetyplease.app.mesh

import kotlinx.coroutines.channels.Channel
import ro.safetyplease.app.protocol.Packet
import ro.safetyplease.app.protocol.PacketType

class Outbound(val packet: Packet, val own: Boolean)

/**
 * Coada de trimitere a unei legaturi. Incidentele trec mereu inaintea chat-ului; cand e plina,
 * pierde intai ce e mai putin important. Nu e thread-safe: se foloseste doar din contextul mesh.
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

    /** Cel mai prioritar element din acest moment; se cheama dupa asteptarea de pacing, nu inainte. */
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
