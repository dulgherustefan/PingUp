package ro.safetyplease.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.ui.common.Nearby
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.mesh_off_text
import ro.safetyplease.ui.resources.mesh_off_title
import ro.safetyplease.ui.resources.net_none
import ro.safetyplease.ui.resources.open_settings
import ro.safetyplease.ui.resources.phones_around
import ro.safetyplease.ui.resources.problem_access_action
import ro.safetyplease.ui.resources.problem_access_text
import ro.safetyplease.ui.resources.problem_access_title
import ro.safetyplease.ui.resources.problem_bt_text
import ro.safetyplease.ui.resources.problem_location_action
import ro.safetyplease.ui.resources.problem_location_text
import ro.safetyplease.ui.resources.problem_location_title
import ro.safetyplease.ui.resources.report_wait_access
import ro.safetyplease.ui.resources.report_wait_bluetooth
import ro.safetyplease.ui.resources.report_wait_location
import ro.safetyplease.ui.resources.report_wait_mesh_off
import ro.safetyplease.ui.resources.status_bt_enable
import ro.safetyplease.ui.resources.status_bt_off
import ro.safetyplease.ui.resources.status_no_access


/** What's missing for the mesh to work, and the actions that fix it. */
class RadioGate(
    val hasAccess: Boolean,
    val bluetoothOn: Boolean,
    val locationOff: Boolean,
    /** Access denied for good: [requestAccess] opens the app settings. */
    val accessBlocked: Boolean,
    val requestAccess: () -> Unit,
    /** Null where an app can't turn Bluetooth on or show where to: the iPhone, where it's in Control Center. */
    val enableBluetooth: (() -> Unit)?,
    /** The user turned the network off; [turnOn] starts it again. */
    val meshOff: Boolean = false,
    val turnOn: () -> Unit = {},
    /** Location services are off, which below Android 12 keeps Bluetooth from finding anyone. */
    val openLocationSettings: () -> Unit = {},
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
            action = gate.enableBluetooth?.let { stringResource(Res.string.status_bt_enable) }, onAction = gate.enableBluetooth ?: {},
        )
        RadioProblem.LOCATION -> Banner(
            stringResource(Res.string.problem_location_title),
            stringResource(if (forReport) Res.string.report_wait_location else Res.string.problem_location_text), modifier, warning = true,
            action = stringResource(Res.string.problem_location_action),
            onAction = gate.openLocationSettings,
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

/**
 * Network dot: green with phones around, gray without, red when the radio can't work.
 * Static on purpose; only the color animates on state changes, slowly, so it doesn't flicker as phones come and go.
 */
@Composable
fun NetworkDot(links: Int, gate: RadioGate) {
    val colors = AppTheme.colors
    val target = when {
        gate.meshOff -> colors.tertiaryLabel
        !gate.hasAccess || !gate.bluetoothOn -> colors.red
        links > 0 -> colors.brand
        else -> colors.tertiaryLabel
    }
    val color by animateColorAsState(target, if (LocalReduceMotion.current) snap() else tween(480), label = "net")
    Box(Modifier.padding(end = 6.dp).size(7.dp).background(color, CircleShape))
}
