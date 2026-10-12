package ro.safetyplease.app.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R
import ro.safetyplease.app.text.agoText
import ro.safetyplease.app.ui.common.RadioGate
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Banner
import ro.safetyplease.app.ui.designsystem.footnote
import ro.safetyplease.app.ui.designsystem.headline
import ro.safetyplease.app.ui.designsystem.subheadline
import ro.safetyplease.app.ui.designsystem.title3
import ro.safetyplease.core.control.HeardPeer
import ro.safetyplease.core.control.PeerBook
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.util.toHex

/** An anchor not heard for this long is called out: control would read its silence as calm. */
private const val SILENT_ANCHOR_MIN = 10
private const val MAX_HEARD_ROWS = 60

/**
 * The network as this console sees it: what stops the radio, the counters since the app started and the phones heard,
 * every anchor among them, with when each was last heard. Anchor heartbeats come later; until then, silence is the
 * only signal.
 */
@Composable
internal fun NetworkPane(mesh: MeshState, heard: Map<Int, HeardPeer>, gate: RadioGate, now: Long, modifier: Modifier) {
    val colors = AppTheme.colors
    val peers = remember(heard) { PeerBook.rows(heard, MAX_HEARD_ROWS) }
    val counters = listOf(
        mesh.readyLinks.toLong() to R.string.anchor_peers,
        mesh.visiblePeers.toLong() to R.string.anchor_visible,
        mesh.cachedReports.toLong() to R.string.anchor_cached,
        mesh.counters.storeAndForward to R.string.control_network_served,
        mesh.sent to R.string.control_network_sent,
        mesh.received to R.string.control_network_received,
        mesh.relayed to R.string.anchor_relayed,
        mesh.dropped to R.string.control_network_dropped,
    )
    Column(modifier.clip(PaneShape).background(colors.cell).verticalScroll(rememberScrollState()).padding(16.dp)) {
        RadioProblem(gate)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = (maxWidth / 150.dp).toInt().coerceIn(2, 4)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                counters.chunked(perRow).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (value, label) -> Counter(value.toString(), stringResource(label), Modifier.weight(1f)) }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Text(stringResource(R.string.control_heard_title), style = MaterialTheme.typography.headline, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        if (peers.isEmpty()) {
            Text(stringResource(R.string.control_heard_empty), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }
        peers.forEach { HeardRow(it, now) }
    }
}

/** What keeps the console off the mesh, with the button that fixes it. */
@Composable
private fun RadioProblem(gate: RadioGate) {
    val modifier = Modifier.padding(bottom = 12.dp)
    when {
        !gate.hasAccess -> Banner(
            stringResource(R.string.problem_access_title), stringResource(R.string.problem_access_text), modifier, warning = true,
            action = stringResource(if (gate.accessBlocked) R.string.open_settings else R.string.problem_access_action),
            onAction = gate.requestAccess,
        )
        !gate.bluetoothOn -> Banner(
            stringResource(R.string.status_bt_off), stringResource(R.string.problem_bt_text), modifier, warning = true,
            action = stringResource(R.string.status_bt_enable), onAction = gate.enableBluetooth,
        )
    }
}

@Composable
private fun Counter(value: String, label: String, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(AppTheme.colors.fill).padding(10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(value, style = MaterialTheme.typography.title3, maxLines = 1)
        Text(label, style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel)
    }
}

/** A phone by its advertised prefix: its role, and whether it's linked now or when it was last heard. */
@Composable
private fun HeardRow(peer: HeardPeer, now: Long) {
    val colors = AppTheme.colors
    val anchor = peer.anchor
    val quietMin = ((now - peer.lastHeardMs) / 60_000).toInt()
    val silent = anchor && !peer.linked && quietMin >= SILENT_ANCHOR_MIN
    val role = when {
        anchor -> stringResource(if (peer.verified) R.string.anchor_verified else R.string.anchor_title)
        peer.flags and NodeFlags.STAFF != 0 -> stringResource(if (peer.verified) R.string.staff_verified else R.string.control_heard_staff)
        else -> ""
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Text(peer.prefix.toHex(), style = MaterialTheme.typography.subheadline.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.width(96.dp))
        Text(role, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.weight(1f))
        Text(
            when {
                peer.linked -> stringResource(R.string.control_heard_linked)
                silent -> pluralStringResource(R.plurals.control_heard_silent, quietMin, quietMin)
                else -> stringResource(R.string.control_heard_at, agoText(peer.lastHeardMs, now))
            },
            style = MaterialTheme.typography.footnote,
            color = when {
                silent -> colors.orangeInk
                peer.linked -> colors.accent
                else -> colors.secondaryLabel
            },
        )
    }
}
