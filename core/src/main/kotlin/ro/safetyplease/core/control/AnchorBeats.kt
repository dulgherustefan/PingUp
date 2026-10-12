package ro.safetyplease.core.control

import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType

/** Anchor heartbeats on the control channel: sealed like a report body, so only staff phones that open reports read them. */
object AnchorBeats {
    fun seal(staff: StaffCrypto, beat: Heartbeat): ByteArray = staff.sealReport(beat.encode())

    /** Null for anything but a heartbeat on the control channel that opens with this phone's staff keys. */
    fun open(staff: StaffCrypto, secret: StaffSecretKeys, packet: Packet): Heartbeat? {
        if (packet.type != PacketType.PRIVATE || packet.recipient != ControlChannel.ID) return null
        val plain = staff.openReport(packet.payload, secret) ?: return null
        return Heartbeat.decode(plain)
    }
}
