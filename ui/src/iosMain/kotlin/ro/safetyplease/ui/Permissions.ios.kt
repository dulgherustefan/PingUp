package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
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
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.ui.common.RadioGate
import ro.safetyplease.ui.common.topViewController
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.mesh_switch_on_kept
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

@Composable
actual fun rememberLocationRequest(vm: AppViewModel, onResult: (Boolean) -> Unit): () -> Unit = {
    // the system asks the person the first time; the answer comes back through the position itself
    vm.phone.startLocation()
    onResult(vm.phone.locationAllowed())
}

/** Notifications at the end of onboarding; the position and Bluetooth are asked as the mesh first starts, one after the other. */
@Composable
actual fun rememberStartPermissions(onDone: () -> Unit): () -> Unit {
    val done by rememberUpdatedState(onDone)
    return {
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(options) { _, _ ->
            dispatch_async(dispatch_get_main_queue()) { done() }
        }
    }
}

actual val RadioNeedsLocation = false

actual val UnfindableIsAProblem = false

actual val MeshOnText: StringResource = Res.string.mesh_switch_on_kept

// no notification has to stay: Bluetooth runs in the background by itself
actual val NotificationsReason: StringResource = Res.string.perm_notifications_text_short

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
