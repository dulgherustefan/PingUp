package ro.safetyplease.app.platform.ble

import ro.safetyplease.core.ble.Advert

/**
 * What field measurements see of the radio, before any throttling. Called from Android's callback threads
 * and from the radio's own, so implementations must be thread-safe.
 */
interface RadioProbe {
    /** Every scan result of our service, with its raw RSSI. */
    fun heard(advert: Advert)

    /** A link's PHY from onPhyRead or onPhyUpdate, as BluetoothDevice.PHY_LE_* values. */
    fun phy(address: String, txPhy: Int, rxPhy: Int)
}
