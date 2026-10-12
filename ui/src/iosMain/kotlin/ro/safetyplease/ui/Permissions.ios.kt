package ro.safetyplease.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.ui.common.RadioGate

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
actual fun KeepLandscape() = Unit
