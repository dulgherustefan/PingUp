package ro.safetyplease.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.control.CellData
import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.control.PointReport
import ro.safetyplease.core.control.SquareCoverage
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.common.pointLevelColor
import ro.safetyplease.ui.common.pointLevelLabel
import ro.safetyplease.ui.designsystem.AppColors
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_cell_fails
import ro.safetyplease.ui.resources.control_cell_no_network
import ro.safetyplease.ui.resources.control_cell_unknown
import ro.safetyplease.ui.resources.control_cell_works
import ro.safetyplease.ui.resources.control_layer_cellular
import ro.safetyplease.ui.resources.control_layer_incidents
import ro.safetyplease.ui.resources.control_layer_mesh
import ro.safetyplease.ui.resources.control_points_age
import ro.safetyplease.ui.resources.control_points_empty
import ro.safetyplease.ui.resources.control_points_none
import ro.safetyplease.ui.resources.control_points_stale
import ro.safetyplease.ui.resources.control_points_title

/** What the console's map shows over the site grid besides the points. */
enum class MapLayer(val label: StringResource) {
    INCIDENTS(Res.string.control_layer_incidents),
    CELLULAR(Res.string.control_layer_cellular),
    MESH(Res.string.control_layer_mesh),
}

/** Each point's color on the map: its last status, grey once nobody confirmed it for half an hour. */
@Composable
fun pointRings(points: Map<Int, PointReport>, now: Long): Map<Int, Color> =
    points.mapValues { (_, report) -> pointLevelColor(report.level, PointBoard.isStale(report, now)) }

/** The squares the anchors cover, colored by [layer]; a square without one stays clear, since nothing is known there. */
fun coverageShades(colors: AppColors, squares: List<SquareCoverage>, layer: MapLayer): Map<Pair<Int, Int>, Color> =
    if (layer == MapLayer.INCIDENTS) {
        emptyMap()
    } else {
        squares.associate { square ->
            val color = if (layer == MapLayer.MESH) {
                if (square.links > 0) colors.green else colors.red
            } else {
                cellColor(colors, square.data)
            }
            (square.column to square.row) to color.copy(alpha = SHADE_ALPHA)
        }
    }

private const val SHADE_ALPHA = 0.32f

fun cellColor(colors: AppColors, data: CellData): Color = when (data) {
    CellData.WORKS -> colors.green
    CellData.FAILS -> colors.orange
    CellData.NO_NETWORK -> colors.red
    CellData.UNKNOWN -> colors.secondaryLabel
}

fun cellText(data: CellData): StringResource = when (data) {
    CellData.WORKS -> Res.string.control_cell_works
    CellData.FAILS -> Res.string.control_cell_fails
    CellData.NO_NETWORK -> Res.string.control_cell_no_network
    CellData.UNKNOWN -> Res.string.control_cell_unknown
}

/**
 * The live board of the site's points: each with its last status, how old it is and the team that said it. A status
 * nobody confirmed for half an hour turns grey and says so; a point nobody reported shows no level at all.
 */
@Composable
fun PointsPane(venue: Venue, points: Map<Int, PointReport>, now: Long, modifier: Modifier) {
    val colors = AppTheme.colors
    val all = remember(venue) { PointBoard.points(venue) }
    Column(modifier.clip(PaneShape).background(colors.cell).verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(Res.string.control_points_title), style = MaterialTheme.typography.headline, modifier = Modifier.padding(bottom = 4.dp))
        when {
            all.isEmpty() -> Text(stringResource(Res.string.control_points_none), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
            points.isEmpty() -> Text(stringResource(Res.string.control_points_empty), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }
        // the worst first: closed, then busy, then free, then the ones nobody reported
        val rows = all.sortedBy { poi -> points[poi.number]?.let { if (PointBoard.isStale(it, now)) 4 else 3 - it.level } ?: 5 }
        for (poi in rows) {
            val report = points[poi.number]
            val stale = report != null && PointBoard.isStale(report, now)
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier.size(12.dp).clip(CircleShape)
                        .background(report?.let { pointLevelColor(it.level, stale) } ?: colors.fill),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(poi.name.ifEmpty { "#${poi.number}" }, style = MaterialTheme.typography.subheadline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (report != null) {
                        val minutes = ((now - report.atMs) / 60_000).toInt().coerceAtLeast(0)
                        Text(
                            if (stale) stringResource(Res.string.control_points_stale, minutes) else stringResource(Res.string.control_points_age, minutes, report.team),
                            style = MaterialTheme.typography.footnote,
                            color = if (stale) colors.orangeInk else colors.secondaryLabel,
                        )
                    }
                }
                if (report != null) {
                    Text(
                        stringResource(pointLevelLabel(report.level)), style = MaterialTheme.typography.footnote, fontWeight = FontWeight.SemiBold,
                        color = if (stale) colors.secondaryLabel else colors.label,
                    )
                }
            }
        }
    }
}
