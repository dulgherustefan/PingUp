package ro.safetyplease.ui

/**
 * What the screens need from the phone itself, past what the session gives: the Android app and the iPhone app each
 * have their own.
 */
interface PhoneUi {
    /**
     * Keeps the mesh running with the screen off, once the person allowed Bluetooth and hasn't turned the network off:
     * Android's foreground service, the iPhone's Bluetooth background modes. Also follows the position.
     */
    fun startMesh()

    fun stopMesh()

    /** The permissions the mesh needs for Bluetooth. */
    fun bluetoothAllowed(): Boolean

    /** Precise location is allowed; the zone and reports work with it. */
    fun locationAllowed(): Boolean

    /** The phone's own location switch: with it off there is no fix, whatever the permission. */
    fun locationOn(): Boolean

    /** Follows the position while the app is in front; no-op without permission. */
    fun startLocation()
}
