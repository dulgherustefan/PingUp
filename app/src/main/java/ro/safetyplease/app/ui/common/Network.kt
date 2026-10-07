package ro.safetyplease.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import ro.safetyplease.app.R
import ro.safetyplease.app.core.nodePrefix
import ro.safetyplease.app.mesh.MeshState
import ro.safetyplease.app.mesh.RadioStatus

/** Ce arata interfata din starea mesh-ului; se schimba doar cand cineva apare, dispare sau se leaga, nu la fiecare pachet. */
data class Nearby(
    val linked: Set<Long> = emptySet(),
    val seenPrefixes: Set<Int> = emptySet(),
    val readyLinks: Int = 0,
    val radio: RadioStatus = RadioStatus(),
) {
    /** Legat direct de noi, nu doar vazut in scanare. */
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

/** Ce lipseste ca reteaua sa mearga si actiunile care rezolva. */
class RadioGate(
    val hasAccess: Boolean,
    val bluetoothOn: Boolean,
    val locationOff: Boolean,
    /** Accesul a fost refuzat definitiv: [requestAccess] deschide setarile aplicatiei. */
    val accessBlocked: Boolean,
    val requestAccess: () -> Unit,
    val enableBluetooth: () -> Unit,
)

/**
 * Starea retelei, sub titlu: un fapt, nu o activitate, ca „Waiting for network…” din Telegram si contorul din bitchat.
 * „Caut…” ramanea pe ecran ore intregi, desi radioul cauta oricum mereu.
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
