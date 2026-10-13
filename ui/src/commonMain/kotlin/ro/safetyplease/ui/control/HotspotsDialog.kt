package ro.safetyplease.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.safety.Hotspot
import ro.safetyplease.core.safety.Hotspots
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.EmptyState
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.ScreenScaffold
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.hotspots_axis_now
import ro.safetyplease.ui.resources.hotspots_axis_start
import ro.safetyplease.ui.resources.hotspots_counts
import ro.safetyplease.ui.resources.hotspots_empty
import ro.safetyplease.ui.resources.hotspots_note
import ro.safetyplease.ui.resources.hotspots_title
import ro.safetyplease.ui.resources.hotspots_unplaced
import ro.safetyplease.ui.text.agoText

/**
 * Where patrols should walk: harassment and "mă simt în nesiguranță" reports of the last 24 hours by square of the
 * site plan and by hour, busiest first. For the console only; the public never sees this.
 */
@Composable
fun HotspotsDialog(staff: List<StaffIncident>, grid: SiteGrid, venue: Venue, now: Long, onDismiss: () -> Unit) {
    val spots = remember(staff, grid, venue, now / 60_000L) { Hotspots.of(staff, grid, venue, now) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        ScreenScaffold(stringResource(Res.string.hotspots_title), onDismiss) { padding ->
            LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        stringResource(Res.string.hotspots_note), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
                        modifier = Modifier.padding(horizontal = Gutter * 2),
                    )
                }
                if (spots.cells.isEmpty()) item { EmptyState(stringResource(Res.string.hotspots_empty), null, icon = Sym.Info) }
                items(spots.cells) { spot -> HotspotRow(spot, now) }
                if (spots.unplaced > 0) {
                    item {
                        Text(
                            stringResource(Res.string.hotspots_unplaced, spots.unplaced), style = MaterialTheme.typography.footnote,
                            color = AppTheme.colors.secondaryLabel, modifier = Modifier.padding(horizontal = Gutter * 2),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HotspotRow(spot: Hotspot, now: Long) {
    val colors = AppTheme.colors
    val peak = spot.perHour.max().coerceAtLeast(1)
    Column(Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.cell).padding(16.dp)) {
        Text(listOfNotNull(spot.cell, spot.zoneName).joinToString(" · "), style = MaterialTheme.typography.headline)
        Text(
            stringResource(Res.string.hotspots_counts, spot.total, spot.harassment, spot.unsafe) + " · " + agoText(spot.lastAtMs, now),
            style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
        )
        Row(Modifier.fillMaxWidth().height(44.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            for (count in spot.perHour) {
                Box(
                    Modifier.weight(1f).fillMaxHeight(if (count == 0) BAR_EMPTY else maxOf(BAR_MIN, count.toFloat() / peak))
                        .clip(RoundedCornerShape(2.dp)).background(if (count == 0) colors.fill else colors.accent),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(Res.string.hotspots_axis_start), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel, modifier = Modifier.weight(1f))
            Text(stringResource(Res.string.hotspots_axis_now), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }
    }
}

private const val BAR_EMPTY = 0.05f
private const val BAR_MIN = 0.15f
