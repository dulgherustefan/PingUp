package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.StringResource
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreBluetooth.CBManagerAuthorizationRestricted
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMask
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionCriticalAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNNotificationSettingDisabled
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.ui.common.RadioGate
import ro.safetyplease.ui.common.topViewController
import ro.safetyplease.ui.ios.IosNotifications
import ro.safetyplease.ui.ios.LocationAsk
import ro.safetyplease.ui.ios.PRECISE_LOCATION_PURPOSE
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.iphone_background_hint
import ro.safetyplease.ui.resources.iphone_checkin_note
import ro.safetyplease.ui.resources.iphone_mesh_on
import ro.safetyplease.ui.resources.perm_notifications_text_short

/**
 * iOS asks for Bluetooth itself the first time the radio starts, and never again: after a refusal, only the app's page
 * in Settings can change it, and Bluetooth is turned on from Control Center.
 */
@Composable
actual fun rememberRadioGate(vm: AppViewModel, radio: RadioStatus, onStartMesh: () -> Unit): RadioGate {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val access = CBManager.authorization
    val refused = access == CBManagerAuthorizationDenied || access == CBManagerAuthorizationRestricted
    return RadioGate(
        vm.phone.bluetoothAllowed(), radio.bluetoothOn, locationOff = false, accessBlocked = refused,
        // not asked yet: starting the radio is what asks
        requestAccess = if (refused) vm.phone::openAppSettings else onStartMesh,
        // nothing an app can open turns Bluetooth on
        enableBluetooth = null,
        meshOff = !settings.meshEnabled,
        turnOn = {
            vm.setMeshEnabled(true)
            onStartMesh()
        },
        openLocationSettings = vm.phone::openLocationSettings,
    )
}

/**
 * iOS asks the person once; the answer comes back to [onResult] when they give it. After a refusal only the app's page
 * in Settings can change it, and an approximate position is asked to be precise for as long as the app is in use.
 */
@Composable
actual fun rememberLocationRequest(vm: AppViewModel, onResult: (Boolean) -> Unit): () -> Unit {
    val result by rememberUpdatedState(onResult)
    val ask = remember { LocationAsk() }
    return {
        fun precise(granted: Boolean) {
            if (granted) vm.phone.startLocation()
            result(granted)
        }
        fun answered(answer: LocationAsk.Answer) {
            vm.locationBlocked = answer == LocationAsk.Answer.REFUSED
            when (answer) {
                LocationAsk.Answer.PRECISE -> precise(true)
                LocationAsk.Answer.APPROXIMATE -> ask.askPrecise(PRECISE_LOCATION_PURPOSE, ::precise)
                LocationAsk.Answer.REFUSED, LocationAsk.Answer.NOT_ASKED -> result(false)
            }
        }
        when (val now = ask.answer()) {
            LocationAsk.Answer.NOT_ASKED -> ask.ask(::answered)
            LocationAsk.Answer.REFUSED -> {
                vm.locationBlocked = true
                vm.phone.openAppSettings()
                result(false)
            }
            else -> answered(now)
        }
    }
}

/** Notifications at the end of onboarding; the position and Bluetooth are asked as the mesh first starts, one after the other. */
@Composable
actual fun rememberStartPermissions(onDone: () -> Unit): () -> Unit {
    val done by rememberUpdatedState(onDone)
    return {
        // Critical Alerts only once Apple granted them and the build turned them on
        val critical = if (IosNotifications.criticalAlerts) UNAuthorizationOptionCriticalAlert else 0uL
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge or critical
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(options) { _, _ ->
            dispatch_async(dispatch_get_main_queue()) { done() }
        }
    }
}

actual val RadioNeedsLocation = false

actual val UnfindableIsAProblem = false

actual val MeshOnText: StringResource = Res.string.iphone_mesh_on

actual val KeepRunningHint: StringResource? = Res.string.iphone_background_hint

actual val CheckinWakeNote: StringResource? = Res.string.iphone_checkin_note

// no notification has to stay: Bluetooth runs in the background by itself
actual val NotificationsReason: StringResource = Res.string.perm_notifications_text_short

@Composable
actual fun rememberNotificationsAllowed(): Boolean {
    // until iOS answers, and when it has never asked, there is nothing to warn about
    var allowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val denied = settings?.authorizationStatus == UNAuthorizationStatusDenied
            dispatch_async(dispatch_get_main_queue()) { allowed = !denied }
        }
        onPauseOrDispose { }
    }
    return allowed
}

/**
 * Notifications may be on with the alarm's sound or its Time Sensitive delivery off, which iOS lets the person choose
 * per app: then a staff alarm neither sounds nor gets through Focus. With Critical Alerts on, those count too.
 */
@Composable
actual fun rememberAlarmsAudible(): Boolean {
    var audible by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            // one not supported (a build without the entitlement) is no setting the person can change
            val off = settings != null && (
                settings.soundSetting == UNNotificationSettingDisabled || settings.timeSensitiveSetting == UNNotificationSettingDisabled ||
                    (IosNotifications.criticalAlerts && settings.criticalAlertSetting == UNNotificationSettingDisabled)
                )
            dispatch_async(dispatch_get_main_queue()) { audible = !off }
        }
        onPauseOrDispose { }
    }
    return audible
}

@Composable
actual fun ConsoleWindow() {
    DisposableEffect(Unit) {
        UIApplication.sharedApplication.idleTimerDisabled = true
        Orientations.landscape = true
        onDispose {
            UIApplication.sharedApplication.idleTimerDisabled = false
            Orientations.landscape = false
        }
    }
}

/**
 * The orientations the app allows now: portrait, and landscape while the phone is the console. Swift's app delegate
 * gives them to iOS, and turning the console on or off rotates the screen.
 */
object Orientations {
    var landscape = false
        set(value) {
            field = value
            rotate()
        }

    val mask: UIInterfaceOrientationMask
        get() = if (landscape) UIInterfaceOrientationMaskLandscape else UIInterfaceOrientationMaskPortrait

    private fun rotate() {
        topViewController()?.setNeedsUpdateOfSupportedInterfaceOrientations()
        val scene = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>().firstOrNull() ?: return
        scene.requestGeometryUpdateWithPreferences(UIWindowSceneGeometryPreferencesIOS(interfaceOrientations = mask), errorHandler = null)
    }
}
