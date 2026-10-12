package ro.safetyplease.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.text.Labels
import ro.safetyplease.app.text.Replies
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.core.data.IncidentUpdate
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue

/**
 * What the reporter said after the report, in the reporter's order, at the reporter's time: one row each, and for a
 * move, where to.
 */
@Composable
fun UpdateTimeline(updates: List<IncidentUpdate>, venue: Venue, grid: SiteGrid, modifier: Modifier = Modifier) {
    Column(modifier) {
        for (u in updates) {
            val (lat, lon) = u.lat to u.lon
            val square = if (lat != null && lon != null) grid.cellAt(lat, lon).orEmpty() else ""
            val place = listOf(placeText(square, u.landmark, u.accuracyM, venue), u.zone?.let { venue.zoneName(it) }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" · ")
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Text(
                    Labels.clock(u.at), style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
                    modifier = Modifier.width(48.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Replies.update(u.kind)), style = MaterialTheme.typography.subheadline)
                    if (place.isNotEmpty()) Text(place, style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel)
                }
            }
        }
    }
}
