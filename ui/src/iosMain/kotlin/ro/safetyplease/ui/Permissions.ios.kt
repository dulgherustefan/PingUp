package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationMask
import platform.UIKit.UIInterfaceOrientationMaskLandscape
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIWindowScene
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.ui.common.RadioGate
import ro.safetyplease.ui.common.topViewController

/**
 * iOS asks for Bluetooth itself the first time the radio starts, and never again: after a refusal, only the app's page
 * in Settings can change it, and Bluetooth is turned on from Control Center.
 */
@Composable
actual fun rememberRadioGate(vm: AppViewModel, radio: RadioStatus, onStartMesh: () -> Unit): RadioGate {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val hasAccess = vm.phone.bluetoothAllowed()
    return RadioGate(
        hasAccess, radio.bluetoothOn, locationOff = false, accessBlocked = !hasAccess,
        requestAccess = vm.phone::openAppSettings,
        enableBluetooth = vm.phone::openAppSettings,
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

@Composable
actual fun rememberStartPermissions(onDone: () -> Unit): () -> Unit = onDone

actual val RadioNeedsLocation = false

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
