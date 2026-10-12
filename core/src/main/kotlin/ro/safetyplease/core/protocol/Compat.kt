package ro.safetyplease.core.protocol

/**
 * Protocol 1.0 counts what it can't read as malformed and bans a peer after five in a minute. A 1.1 node sends a
 * peer that didn't announce [NodeFlags.PROTOCOL_1_1] only what passes here.
 */
object Compat10 {
    fun accepts(packet: Packet): Boolean = when (packet.type) {
        PacketType.INCIDENT_ACK -> IncidentAck.decode(packet.payload)
            ?.let { AckStatus.isKnown(it.status) && it.extensions.isEmpty() } ?: false
        PacketType.INCIDENT_CANCEL -> packet.payload.size == IncidentCancel.SIZE
        else -> packet.type in PacketType.V1_0
    }
}
