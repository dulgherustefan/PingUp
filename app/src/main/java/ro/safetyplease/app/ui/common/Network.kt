package ro.safetyplease.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ro.safetyplease.app.R
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
