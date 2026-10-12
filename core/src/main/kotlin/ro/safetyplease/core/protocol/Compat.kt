package ro.safetyplease.core.protocol

/**
 * Protocol 1.0 counts what it can't read as malformed and bans a peer after five in a minute. A 1.1 node sends a
 * peer that didn't announce [NodeFlags.PROTOCOL_1_1] only what passes here: never a response or an update.
 */
object Compat10 {
    fun accepts(packet: Packet): Boolean = when (packet.type) {
        PacketType.INCIDENT_ACK -> IncidentAck.decode(packet.payload)
            ?.let { it.status in AckStatus.V1_0 && it.extensions.isEmpty() } ?: false
        PacketType.INCIDENT_CANCEL -> packet.payload.size == IncidentCancel.SIZE
        else -> packet.type in PacketType.V1_0
    }
}

/**
 * An ACK signed under a certificate goes only to a peer that set [NodeFlags.HELLO_AUTH]: a 1.1 peer from before it
 * checks every ACK against the build's staff key, so it would count this one as malformed. A response under a
 * certificate doesn't go to it either: it would only relay it as a type it doesn't read, and drop it for its size.
 */
object CompatAuth {
    fun needsAuth(packet: Packet): Boolean = when (packet.type) {
        PacketType.INCIDENT_ACK -> IncidentAck.decode(packet.payload)?.certified == true
        PacketType.INCIDENT_RESPONSE -> IncidentResponse.decode(packet.payload)?.certificate != null
        else -> false
    }
}
