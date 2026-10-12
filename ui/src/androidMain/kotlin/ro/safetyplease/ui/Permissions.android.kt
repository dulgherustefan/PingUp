package ro.safetyplease.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.ui.common.RadioGate
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.location_precise_needed

/** The mesh can't start without these. */
fun radioPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        // below Android 12 BLE scanning needs the location permission
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

actual val RadioNeedsLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

/** Asked at startup: Bluetooth and notifications. Location is asked only when it's needed. */
fun startPermissions(): Array<String> = buildList {
    addAll(radioPermissions())
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

fun bluetoothEnabled(context: Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

/**
 * After two refusals Android stops showing the permission dialog. This is only knowable after a request:
 * before the first one, no rationale just means we haven't asked yet.
 */
fun deniedForGood(activity: Activity?, denied: Collection<String>): Boolean =
    activity != null && denied.any { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }

fun openAppSettings(context: Context) {
    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
}

@Composable
actual fun rememberRadioGate(vm: AppViewModel, radio: RadioStatus, onStartMesh: () -> Unit): RadioGate {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // the mesh works without notifications; only the radio's permissions matter
        val denied = result.filter { !it.value && it.key in radioPermissions() }.keys
        vm.radioAccessBlocked = deniedForGood(activity, denied)
        tick++
        onStartMesh()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        tick++
        onStartMesh()
    }
    // tick and radio state force a re-check when the user comes back from the dialogs
    val hasAccess = remember(tick, radio) { vm.phone.bluetoothAllowed() }
    val bluetoothOn = remember(tick, radio) { bluetoothEnabled(context) }
    val locationOff = remember(tick, radio) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.getSystemService(LocationManager::class.java)?.let { !LocationManagerCompat.isLocationEnabled(it) } == true
    }
    val blocked = vm.radioAccessBlocked && !hasAccess
    return RadioGate(
        hasAccess, bluetoothOn, locationOff, blocked,
        requestAccess = { if (blocked) openAppSettings(context) else permissionLauncher.launch(startPermissions()) },
        enableBluetooth = { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
        meshOff = !settings.meshEnabled,
        turnOn = {
            vm.setMeshEnabled(true)
            onStartMesh()
        },
        openLocationSettings = vm.phone::openLocationSettings,
    )
}

@Composable
actual fun rememberLocationRequest(vm: AppViewModel, onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val preciseNeeded = stringResource(Res.string.location_precise_needed)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            vm.phone.startLocation()
        } else {
            vm.locationBlocked = deniedForGood(activity, listOf(Manifest.permission.ACCESS_FINE_LOCATION))
            if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) Toast.makeText(context, preciseNeeded, Toast.LENGTH_LONG).show()
        }
        onResult(granted)
    }
    return {
        when {
            vm.phone.locationAllowed() -> {
                vm.phone.startLocation()
                onResult(true)
            }
            vm.locationBlocked -> openAppSettings(context)
            else -> launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
}

@Composable
actual fun rememberStartPermissions(onDone: () -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    return { launcher.launch(startPermissions()) }
}

@Composable
actual fun KeepLandscape() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { if (before != null) activity.requestedOrientation = before }
    }
}
