package ro.safetyplease.app.ui.common

import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.designsystem.Banner
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.util.nodePrefix

/** What the UI shows from mesh state; changes only when someone appears, leaves or links, not on every packet. */
data class Nearby(
    val linked: Set<Long> = emptySet(),
    val seenPrefixes: Set<Int> = emptySet(),
    val readyLinks: Int = 0,
    val radio: RadioStatus = RadioStatus(),
) {
    /** Directly linked to us, not just seen in a scan. */
    fun isLinked(nodeId: Long): Boolean = nodeId in linked

    fun isInRange(nodeId: Long): Boolean = isLinked(nodeId) || nodeId.nodePrefix() in seenPrefixes

    companion object {
        fun of(state: MeshState) = Nearby(
            state.links.mapTo(HashSet()) { it.peerId },
            state.seen.mapNotNullTo(HashSet()) { it.prefix },
            state.readyLinks,
            state.radio,
        )
    }
}

/** What's missing for the mesh to work, and the actions that fix it. */
class RadioGate(
    val hasAccess: Boolean,
    val bluetoothOn: Boolean,
    val locationOff: Boolean,
    /** Access denied for good: [requestAccess] opens the app settings. */
    val accessBlocked: Boolean,
    val requestAccess: () -> Unit,
    val enableBluetooth: () -> Unit,
)

/** What stops the phone from sending anything, the most basic missing piece first. */
enum class RadioProblem { ACCESS, BLUETOOTH, LOCATION }

fun RadioGate.problem(): RadioProblem? = when {
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
        !gate.hasAccess -> stringResource(R.string.status_no_access)
        !gate.bluetoothOn -> stringResource(R.string.status_bt_off)
        links == 0 -> stringResource(R.string.net_none)
        else -> pluralStringResource(R.plurals.phones_around, links, links)
    }
}
