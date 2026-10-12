package ro.safetyplease.app.ui.common

import ro.safetyplease.ui.resources.mesh_off_text
import ro.safetyplease.ui.resources.open_settings
import ro.safetyplease.ui.resources.problem_access_action
import ro.safetyplease.ui.resources.problem_access_text
import ro.safetyplease.ui.resources.problem_bt_text
import ro.safetyplease.ui.resources.problem_location_text
import ro.safetyplease.ui.resources.report_wait_access
import ro.safetyplease.ui.resources.report_wait_bluetooth
import ro.safetyplease.ui.resources.report_wait_location
import ro.safetyplease.ui.resources.report_wait_mesh_off
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.mesh_off_title
import ro.safetyplease.ui.resources.net_none
import ro.safetyplease.ui.resources.phones_around
import ro.safetyplease.ui.resources.problem_access_title
import ro.safetyplease.ui.resources.problem_location_action
import ro.safetyplease.ui.resources.problem_location_title
import ro.safetyplease.ui.resources.status_bt_enable
import ro.safetyplease.ui.resources.status_bt_off
import ro.safetyplease.ui.resources.status_no_access
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
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
            stringResource(Res.string.mesh_off_title),
            stringResource(if (forReport) Res.string.report_wait_mesh_off else Res.string.mesh_off_text), modifier, warning = true,
            action = stringResource(Res.string.status_bt_enable), onAction = gate.turnOn,
        )
        RadioProblem.ACCESS -> Banner(
            stringResource(Res.string.problem_access_title),
            stringResource(if (forReport) Res.string.report_wait_access else Res.string.problem_access_text), modifier, warning = true,
            action = stringResource(if (gate.accessBlocked) Res.string.open_settings else Res.string.problem_access_action),
            onAction = gate.requestAccess,
        )
        RadioProblem.BLUETOOTH -> Banner(
            stringResource(Res.string.status_bt_off),
            stringResource(if (forReport) Res.string.report_wait_bluetooth else Res.string.problem_bt_text), modifier, warning = true,
            action = stringResource(Res.string.status_bt_enable), onAction = gate.enableBluetooth,
        )
        RadioProblem.LOCATION -> Banner(
            stringResource(Res.string.problem_location_title),
            stringResource(if (forReport) Res.string.report_wait_location else Res.string.problem_location_text), modifier, warning = true,
            action = stringResource(Res.string.problem_location_action),
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
        gate.meshOff -> stringResource(Res.string.mesh_off_title)
        !gate.hasAccess -> stringResource(Res.string.status_no_access)
        !gate.bluetoothOn -> stringResource(Res.string.status_bt_off)
        links == 0 -> stringResource(Res.string.net_none)
        else -> pluralStringResource(Res.plurals.phones_around, links, links)
    }
}
