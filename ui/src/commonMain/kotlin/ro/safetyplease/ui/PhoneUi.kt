package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import okio.Path
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.common.Beacon
import ro.safetyplease.ui.common.Torch
import ro.safetyplease.ui.map.OfflineMap
import ro.safetyplease.ui.me.GnssView

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

    /** A report is being written: a fix every second while [on], where the phone paces its GPS itself. Calls nest. */
    fun urgentLocation(on: Boolean)

    /**
     * A location share started or ended, said from a visible screen: Android's foreground service may take the location
     * type only then. The iPhone app shares only while it's in front, so there it does nothing.
     */
    fun sharingLocation(on: Boolean)

    /** Where the phone faces now, clockwise from north, for the arrow to a friend; null when unknown. */
    fun heading(): Float?

    /** The offline base map; null where the drawn venue map stands in, as on the iPhone. */
    val offlineMap: OfflineMap?

    /** The GNSS chip for the hidden diagnostic panel; null where the phone doesn't report it, as on the iPhone. */
    val gnss: GnssView?

    /** Opens the phone app with 112 filled in; the person places the call. */
    fun dial112()

    /**
     * Posts, or puts in place of the last one, the notification "Locația ta pentru 112" with [text], to read during the
     * call; a tap opens the 112 card. It goes when dismissed, and on Android after 30 minutes.
     */
    fun location112(title: String, text: String)

    /** The phone's own location settings, to turn location on. */
    fun openLocationSettings()

    /** This app's page in the phone's settings, for a permission refused for good. */
    fun openAppSettings()

    /** This app's notification settings, to turn them back on. */
    fun openNotificationSettings()

    /**
     * Whether an urgent alert may take the whole screen of a locked phone: Android 14+ lets the person, or the store,
     * refuse that to an app that isn't an alarm clock or a phone. True where nothing can refuse it.
     */
    fun fullScreenAlertsAllowed(): Boolean

    /** The phone's own setting for alerts that take the whole screen; the app's notification settings where it has none. */
    fun openFullScreenAlertSettings()

    /**
     * While shown, an alert that needs no unlocking to act on (an evacuation, "Ești OK?", a responder's call) shows over
     * the lock screen and wakes it. True while it does, so the caller covers what lies behind it; false where the phone
     * can't, as on the iPhone.
     */
    @Composable
    fun ShowOverLockScreen(): Boolean

    /** A short note at the bottom of the screen, gone by itself; [long] keeps it a little longer. */
    fun toast(text: String, long: Boolean = false)

    fun copyText(text: String)

    /** The share sheet for [files]; [onShared] once the person picked where they go, never when they back out. */
    fun shareFiles(files: List<Path>, onShared: (() -> Unit)? = null)

    /** The flash as a torch, followed only while the screen that shows it is up. */
    fun torch(): Torch

    /** Vibration and, with [sound], a beep, in step with the torch for "Fă-te văzut". */
    fun beacon(sound: Boolean): Beacon

    /** Keeps the screen on while it's shown. */
    @Composable
    fun KeepScreenOn()

    /**
     * The phone may stop the app in the background, or not start it again after it did, unless the person exempts it
     * from battery saving: every Android 12+ phone, and the makers known for it on older ones. False once exempted.
     */
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
