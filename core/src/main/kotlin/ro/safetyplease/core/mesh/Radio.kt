package ro.safetyplease.core.mesh

import kotlinx.coroutines.channels.ReceiveChannel

enum class PowerMode { LOW_POWER, BALANCED, LOW_LATENCY }

sealed interface RadioEvent {
    /** Un advertising al serviciului nostru. [prefix] lipseste daca scan response-ul nu a fost primit. */
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
    /** False pe telefoanele fara mod peripheral: pot doar scana si initia conexiuni. */
    val canAdvertise: Boolean = true,
    val codedPhy: Boolean = false,
    val extendedAdvertising: Boolean = false,
    val maxAdvertisingDataLength: Int = 0,
    val multipleAdvertisement: Boolean = false,
    /** Setul de advertising pe Coded PHY ruleaza. */
    val longRangeActive: Boolean = false,
)

/**
 * Tot ce stie motorul de mesh despre radio. Implementarea reala e BLE; testele folosesc una in memorie.
 * Evenimentele sosesc in ordine pe [events]; restul apelurilor se fac din contextul mesh.
 */
interface Radio {
    val events: ReceiveChannel<RadioEvent>

    fun connect(address: String)

    fun disconnect(link: Int)

    /** Trimite un cadru; revine cand stiva l-a preluat. False inseamna legatura stricata. */
    suspend fun send(link: Int, frame: ByteArray): Boolean

    fun setAdvertisedFlags(flags: Int)

    fun setPowerMode(scan: PowerMode, advertise: PowerMode)
}
