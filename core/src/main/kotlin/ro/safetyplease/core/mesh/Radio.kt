package ro.safetyplease.core.mesh

import kotlinx.coroutines.channels.ReceiveChannel

enum class PowerMode { LOW_POWER, BALANCED, LOW_LATENCY }

sealed interface RadioEvent {
    /** An advertisement of our service. [prefix] is missing if the scan response wasn't received. */
    class PeerSeen(val address: String, val prefix: Int?, val flags: Int, val rssi: Int) : RadioEvent

    class LinkUp(val link: Int, val address: String, val outgoing: Boolean, val maxFrame: Int) : RadioEvent

    class LinkDown(val link: Int) : RadioEvent

    class Frame(val link: Int, val bytes: ByteArray) : RadioEvent

    class ConnectFailed(val address: String) : RadioEvent

    class Status(val status: RadioStatus) : RadioEvent
}

data class RadioStatus(
    val bluetoothOn: Boolean = false,
    val scanning: Boolean = false,
    val advertising: Boolean = false,
    /** False on phones without peripheral mode: they can only scan and initiate connections. */
    val canAdvertise: Boolean = true,
    val codedPhy: Boolean = false,
    val extendedAdvertising: Boolean = false,
    val maxAdvertisingDataLength: Int = 0,
    val multipleAdvertisement: Boolean = false,
    /** The Coded PHY advertising set is running. */
    val longRangeActive: Boolean = false,
)

/**
 * Everything the mesh engine knows about the radio. The real one is BLE; tests use an in-memory one.
 * Events arrive in order on [events]; all other calls happen on the mesh context.
 */
interface Radio {
    val events: ReceiveChannel<RadioEvent>

    fun connect(address: String)

    fun disconnect(link: Int)

    /** Sends a frame; returns once the stack has taken it. False means the link is broken. */
    suspend fun send(link: Int, frame: ByteArray): Boolean

    fun setAdvertisedFlags(flags: Int)

    fun setPowerMode(scan: PowerMode, advertise: PowerMode)
}
