package ro.safetyplease.app.ui.control

import ro.safetyplease.ui.resources.anchor_cached
import ro.safetyplease.ui.resources.anchor_peers
import ro.safetyplease.ui.resources.anchor_relayed
import ro.safetyplease.ui.resources.anchor_title
import ro.safetyplease.ui.resources.anchor_verified
import ro.safetyplease.ui.resources.anchor_visible
import ro.safetyplease.ui.resources.control_heard_staff
import ro.safetyplease.ui.resources.control_network_dropped
import ro.safetyplease.ui.resources.control_network_received
import ro.safetyplease.ui.resources.control_network_sent
import ro.safetyplease.ui.resources.control_network_served
import ro.safetyplease.ui.resources.open_settings
import ro.safetyplease.ui.resources.problem_access_action
import ro.safetyplease.ui.resources.staff_verified
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_beat_battery
import ro.safetyplease.ui.resources.control_beat_crowd
import ro.safetyplease.ui.resources.control_beat_indicative
import ro.safetyplease.ui.resources.control_beat_links
import ro.safetyplease.ui.resources.control_beat_mobile
import ro.safetyplease.ui.resources.control_beat_plugged
import ro.safetyplease.ui.resources.control_beat_quiet
import ro.safetyplease.ui.resources.control_beat_signal
import ro.safetyplease.ui.resources.control_beat_square
import ro.safetyplease.ui.resources.control_beat_temperature
import ro.safetyplease.ui.resources.control_heard_at
import ro.safetyplease.ui.resources.control_heard_empty
import ro.safetyplease.ui.resources.control_heard_linked
import ro.safetyplease.ui.resources.control_heard_silent
import ro.safetyplease.ui.resources.control_heard_title
import ro.safetyplease.ui.resources.problem_access_text
import ro.safetyplease.ui.resources.problem_access_title
import ro.safetyplease.ui.resources.problem_bt_text
import ro.safetyplease.ui.resources.status_bt_enable
import ro.safetyplease.ui.resources.status_bt_off
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.app.ui.common.RadioGate
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title3
import ro.safetyplease.core.control.Coverage
import ro.safetyplease.core.control.HeardPeer
import ro.safetyplease.core.control.PeerBook
import ro.safetyplease.core.mesh.AnchorHeartbeat
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.MobileReading
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.SiteGrid

/** An anchor not heard for this long is called out: control would read its silence as calm. */
private const val SILENT_ANCHOR_MIN = 10
private const val MAX_HEARD_ROWS = 60

/** An anchor that has heard no phone for this long says so on the console. */
private const val QUIET_S = 5 * 60

/**
 * The network as this console sees it: what stops the radio, the counters since the app started and the phones heard,
 * every anchor among them, with when each was last heard and what its last heartbeat said. An anchor whose heartbeats
 * stop goes silent wherever it is.
 */
@Composable
internal fun NetworkPane(mesh: MeshState, heard: Map<Int, HeardPeer>, gate: RadioGate, now: Long, grid: SiteGrid, modifier: Modifier) {
    val colors = AppTheme.colors
    val peers = remember(heard) { PeerBook.rows(heard, MAX_HEARD_ROWS) }
    val counters = listOf(
        mesh.readyLinks.toLong() to Res.string.anchor_peers,
        mesh.visiblePeers.toLong() to Res.string.anchor_visible,
        mesh.cachedReports.toLong() to Res.string.anchor_cached,
        mesh.counters.storeAndForward to Res.string.control_network_served,
        mesh.sent to Res.string.control_network_sent,
        mesh.received to Res.string.control_network_received,
        mesh.relayed to Res.string.anchor_relayed,
        mesh.dropped to Res.string.control_network_dropped,
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
        Text(stringResource(Res.string.control_heard_title), style = MaterialTheme.typography.headline, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
        if (peers.isEmpty()) {
            Text(stringResource(Res.string.control_heard_empty), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }
        peers.forEach { HeardRow(it, now, grid) }
    }
}

/** What keeps the console off the mesh, with the button that fixes it. */
@Composable
private fun RadioProblem(gate: RadioGate) {
    val modifier = Modifier.padding(bottom = 12.dp)
    when {
        !gate.hasAccess -> Banner(
            stringResource(Res.string.problem_access_title), stringResource(Res.string.problem_access_text), modifier, warning = true,
            action = stringResource(if (gate.accessBlocked) Res.string.open_settings else Res.string.problem_access_action),
            onAction = gate.requestAccess,
        )
        !gate.bluetoothOn -> Banner(
            stringResource(Res.string.status_bt_off), stringResource(Res.string.problem_bt_text), modifier, warning = true,
            action = stringResource(Res.string.status_bt_enable), onAction = gate.enableBluetooth,
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
private fun HeardRow(peer: HeardPeer, now: Long, grid: SiteGrid) {
    val colors = AppTheme.colors
    val anchor = peer.anchor
    val quietMin = ((now - peer.lastHeardMs) / 60_000).toInt()
    val silent = anchor && !peer.linked && quietMin >= SILENT_ANCHOR_MIN
    val role = when {
        anchor -> stringResource(if (peer.verified) Res.string.anchor_verified else Res.string.anchor_title)
        peer.flags and NodeFlags.STAFF != 0 -> stringResource(if (peer.verified) Res.string.staff_verified else Res.string.control_heard_staff)
        else -> ""
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(peer.prefix.toHex(), style = MaterialTheme.typography.subheadline.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.width(96.dp))
            Text(role, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.weight(1f))
            Text(
                when {
                    peer.linked -> stringResource(Res.string.control_heard_linked)
                    silent -> pluralStringResource(Res.plurals.control_heard_silent, quietMin, quietMin)
                    else -> stringResource(Res.string.control_heard_at, agoText(peer.lastHeardMs, now))
                },
                style = MaterialTheme.typography.footnote,
                color = when {
                    silent -> colors.orangeInk
                    peer.linked -> colors.accent
                    else -> colors.secondaryLabel
                },
            )
        }
        if (anchor) peer.beat?.let { BeatLine(it, grid) }
    }
}

/**
 * What an anchor's last heartbeat said, with its square and the mobile network it measured when it says them.
 * Nothing signs heartbeats yet, so the line says it is only indicative.
 */
@Composable
private fun BeatLine(beat: Heartbeat, grid: SiteGrid) {
    val colors = AppTheme.colors
    val parts = buildList {
        val column = beat.column
        val row = beat.row
        if (column != null && row != null) add(stringResource(Res.string.control_beat_square, grid.columnLabel(column) + grid.rowLabel(row)))
        beat.mobile?.let { mobile ->
            val technology = MobileReading.TECHNOLOGIES[mobile.technology].uppercase().ifEmpty { stringResource(Res.string.control_beat_mobile) }
            mobile.level?.let { add(stringResource(Res.string.control_beat_signal, technology, it)) }
            add(stringResource(cellText(Coverage.data(mobile))))
        }
        beat.batteryPercent?.let { add(stringResource(Res.string.control_beat_battery, "$it%")) }
        if (beat.charging) add(stringResource(Res.string.control_beat_plugged))
        beat.temperatureC?.let { add(stringResource(Res.string.control_beat_temperature, it)) }
        add(pluralStringResource(Res.plurals.control_beat_links, beat.links, beat.links))
        add(stringResource(Res.string.control_beat_crowd, crowdText(beat.crowd)))
        beat.quietS?.takeIf { it >= QUIET_S }?.let { add(stringResource(Res.string.control_beat_quiet, it / 60)) }
        add(stringResource(Res.string.control_beat_indicative))
    }
    val lowBattery = !beat.charging && (beat.batteryPercent ?: 100) < PowerPolicy.LOW_BATTERY_PERCENT
    val warning = lowBattery || (beat.temperatureC ?: 0) >= AnchorHeartbeat.HOT_C || (beat.quietS ?: 0) >= SILENT_ANCHOR_MIN * 60
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.footnote,
        color = if (warning) colors.orangeInk else colors.secondaryLabel,
        modifier = Modifier.padding(start = 96.dp, bottom = 6.dp),
    )
}

/** The bucket as a range of phones: 0, 1-2, 3-5 ... 100+. */
private fun crowdText(bucket: Int): String {
    val low = Heartbeat.CROWD[bucket]
    val next = Heartbeat.CROWD.getOrNull(bucket + 1) ?: return "${low - 1}+"
    return if (next - 1 == low) "$low" else "$low–${next - 1}"
}
