package ro.safetyplease.app.ui.common

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ro.safetyplease.app.R
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.ui.common.Nearby
import ro.safetyplease.ui.designsystem.Banner


/** What's missing for the mesh to work, and the actions that fix it. */
class RadioGate(
    val hasAccess: Boolean,
    val bluetoothOn: Boolean,
    val locationOff: Boolean,
    /** Access denied for good: [requestAccess] opens the app settings. */
    val accessBlocked: Boolean,
    val requestAccess: () -> Unit,
    val enableBluetooth: () -> Unit,
    /** The user turned the network off; [turnOn] starts it again. */
    val meshOff: Boolean = false,
    val turnOn: () -> Unit = {},
)

/** What stops the phone from sending anything, the most basic missing piece first. */
enum class RadioProblem { OFF, ACCESS, BLUETOOTH, LOCATION }

fun RadioGate.problem(): RadioProblem? = when {
    meshOff -> RadioProblem.OFF
    !hasAccess -> RadioProblem.ACCESS
    !bluetoothOn -> RadioProblem.BLUETOOTH
    locationOff -> RadioProblem.LOCATION
    else -> null
}

/**
 * Banner for [RadioGate.problem], with the button that fixes it. On the report screens the text says the report is
 * waiting, since that's what matters to whoever is reporting.
 */
@Composable
fun RadioProblemBanner(gate: RadioGate, modifier: Modifier = Modifier, forReport: Boolean = false) {
    val context = LocalContext.current
    when (gate.problem()) {
        RadioProblem.OFF -> Banner(
            stringResource(R.string.mesh_off_title),
            stringResource(if (forReport) R.string.report_wait_mesh_off else R.string.mesh_off_text), modifier, warning = true,
            action = stringResource(R.string.status_bt_enable), onAction = gate.turnOn,
        )
        RadioProblem.ACCESS -> Banner(
            stringResource(R.string.problem_access_title),
            stringResource(if (forReport) R.string.report_wait_access else R.string.problem_access_text), modifier, warning = true,
            action = stringResource(if (gate.accessBlocked) R.string.open_settings else R.string.problem_access_action),
            onAction = gate.requestAccess,
        )
        RadioProblem.BLUETOOTH -> Banner(
            stringResource(R.string.status_bt_off),
            stringResource(if (forReport) R.string.report_wait_bluetooth else R.string.problem_bt_text), modifier, warning = true,
            action = stringResource(R.string.status_bt_enable), onAction = gate.enableBluetooth,
        )
        RadioProblem.LOCATION -> Banner(
            stringResource(R.string.problem_location_title),
            stringResource(if (forReport) R.string.report_wait_location else R.string.problem_location_text), modifier, warning = true,
            action = stringResource(R.string.problem_location_action),
            onAction = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
        )
        null -> Unit
    }
}

/**
 * Network state under the title. States a fact ("no phones around") rather than an activity,
 * since the radio is always searching anyway.
 */
@Composable
fun networkText(links: Int, gate: RadioGate): String {
    return when {
        gate.meshOff -> stringResource(R.string.mesh_off_title)
        !gate.hasAccess -> stringResource(R.string.status_no_access)
        !gate.bluetoothOn -> stringResource(R.string.status_bt_off)
        links == 0 -> stringResource(R.string.net_none)
        else -> pluralStringResource(R.plurals.phones_around, links, links)
    }
}
