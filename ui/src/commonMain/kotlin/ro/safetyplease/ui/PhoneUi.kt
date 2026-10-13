package ro.safetyplease.ui

import okio.Path
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.common.Torch

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

    /** Opens the phone app with 112 filled in; the person places the call. */
    fun dial112()

    /** The phone's own location settings, to turn location on. */
    fun openLocationSettings()

    /** This app's page in the phone's settings, for a permission refused for good. */
    fun openAppSettings()

    /** This app's notification settings, to turn them back on. */
    fun openNotificationSettings()

    /** A short note at the bottom of the screen, gone by itself; [long] keeps it a little longer. */
    fun toast(text: String, long: Boolean = false)

    fun copyText(text: String)

    /** The share sheet for [files]; [onShared] once the person picked where they go, never when they back out. */
    fun shareFiles(files: List<Path>, onShared: (() -> Unit)? = null)

    /** The flash as a torch, followed only while the screen that shows it is up. */
    fun torch(): Torch

    /** The phone's maker stops apps like this one in the background unless the person exempts it. */
    fun batteryHintNeeded(): Boolean

    /** What to do on this phone to keep the app running, when its maker needs steps of its own. */
    val batterySteps: StringResource?

    /** Asks to exempt the app from battery saving, or opens the settings where that's done. */
    fun requestBatteryExemption()

    /** "1.2.3", as the store shows it. */
    val appVersion: String

    /** The app's name, as the phone shows it. */
    val appName: String

    /** This build carries the test tools: demo mode, a simulated location. */
    val demoAvailable: Boolean
}
