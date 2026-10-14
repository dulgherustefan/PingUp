package ro.safetyplease.core.control

import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType

/**
 * The control channel's payloads, anchor heartbeats and point statuses: sealed like a report body, so only staff
 * phones that open reports read them.
 */
object ControlMessages {
    fun seal(staff: StaffCrypto, plain: ByteArray): ByteArray? = staff.sealReport(plain)

    /** The plaintext, whose first byte is the kind; null for anything else or what this phone's staff keys don't open. */
    fun open(staff: StaffCrypto, secret: StaffSecretKeys, packet: Packet): ByteArray? {
        if (packet.type != PacketType.PRIVATE || packet.recipient != ControlChannel.ID) return null
        return staff.openReport(packet.payload, secret)?.takeIf { it.isNotEmpty() }
    }
}
