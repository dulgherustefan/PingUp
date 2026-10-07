package ro.safetyplease.app.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.caption1
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

class MapPin(val point: GeoPoint, val color: Color, val key: String = "")

private val MapInset = 14.dp
private val CompactInset = 8.dp

/** Smallest zone label size; anything smaller is unreadable on a phone. */
private val MinLabelSize = 9.sp

/** Soft shadow under markers so the white ring stays visible on light zones. */
private val MarkerShadow = Color(0x29000000)

/** Equirectangular projection over the venue bounds; the error is negligible at festival scale. */
private class Projection(venue: Venue, size: Size, pad: Float) {
    private val b = venue.bounds
    private val lonScale = cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    private val worldW = (b.maxLon - b.minLon) * lonScale
    private val worldH = b.maxLat - b.minLat
    // keep zones off the map edge
    private val scale = min((size.width - 2 * pad) / worldW, (size.height - 2 * pad) / worldH)
    private val offX = (size.width - worldW * scale) / 2
    private val offY = (size.height - worldH * scale) / 2

    fun project(p: GeoPoint) = Offset(
        (offX + (p.lon - b.minLon) * lonScale * scale).toFloat(),
        (offY + (b.maxLat - p.lat) * scale).toFloat(),
    )

    fun unproject(o: Offset) = GeoPoint(b.maxLat - (o.y - offY) / scale, b.minLon + (o.x - offX) / (lonScale * scale))
}

fun venueAspect(venue: Venue): Float {
    val b = venue.bounds
    val w = (b.maxLon - b.minLon) * cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    return (w / (b.maxLat - b.minLat)).toFloat()
}

/** Distance in meters between two nearby points; accurate enough within one event. */
fun distanceMeters(a: GeoPoint, b: GeoPoint): Int {
    val metersPerDegree = 111_320.0
    val dLat = (a.lat - b.lat) * metersPerDegree
    val dLon = (a.lon - b.lon) * metersPerDegree * cos(Math.toRadians((a.lat + b.lat) / 2))
    return hypot(dLat, dLon).roundToInt()
}

/**
 * Schematic venue map drawn on a Canvas: no tiles, no internet. Zones use the avatar pastels, names in their dark tint.
 * [myZone] gets an outline, [highlightZone] an outline in its own tint; you are the dot with a halo.
 * [compact] is the message bubble variant: no names, gray zones, only [highlightZone] colored.
 */
@Composable
fun VenueMap(
    venue: Venue,
    modifier: Modifier = Modifier,
    position: GeoPoint? = null,
    pins: List<MapPin> = emptyList(),
    myZone: String = "",
    highlightZone: String = "",
    meetingFocused: Boolean = false,
    compact: Boolean = false,
    onTap: ((GeoPoint, Zone?) -> Unit)? = null,
    onPinTap: ((MapPin) -> Unit)? = null,
) {
    val colors = AppTheme.colors
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val flag = rememberVectorPainter(Sym.Flag)
    val currentPins by rememberUpdatedState(pins)
    val currentTap by rememberUpdatedState(onTap)
    val currentPinTap by rememberUpdatedState(onPinTap)
    val inset = if (compact) CompactInset else MapInset

    Box(
        modifier.fillMaxWidth().aspectRatio(venueAspect(venue))
            // translucent background so the bubble color shows through and the map doesn't vanish into the screen background
            .then(if (compact) Modifier else Modifier.clip(RoundedCornerShape(26.dp))).background(colors.fill),
    ) {
        Canvas(
            Modifier.fillMaxSize().then(
                if (onTap == null && onPinTap == null) Modifier else Modifier.pointerInput(venue, inset) {
                    detectTapGestures { offset ->
                        val projection = Projection(venue, Size(size.width.toFloat(), size.height.toFloat()), inset.toPx())
                        val hit = currentPins.minByOrNull { (projection.project(it.point) - offset).getDistance() }
                        if (hit != null && currentPinTap != null && (projection.project(hit.point) - offset).getDistance() < 28.dp.toPx()) {
                            currentPinTap?.invoke(hit)
                        } else {
                            val point = projection.unproject(offset)
                            if (venue.bounds.contains(point.lat, point.lon)) currentTap?.invoke(point, venue.zoneAt(point.lat, point.lon))
                        }
                    }
                },
            ),
        ) {
            val projection = Projection(venue, size, inset.toPx())
            val marker = position?.let(projection::project)
            val meeting = if (compact) null else venue.meetingPoint?.let(projection::project)
            val spots = pins.map { projection.project(it.point) }
            // things drawn on top of the zones; zone names avoid them
            val marks = listOfNotNull(marker, meeting) + spots
            val paths = venue.zones.map { zone ->
                Path().apply {
                    zone.polygon.forEachIndexed { i, p ->
                        val o = projection.project(p)
                        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                    close()
                }
            }

            venue.zones.forEachIndexed { index, zone ->
                val fill = if (compact && zone.id != highlightZone) colors.cell else colors.zoneFill(index, zone.id)
                drawPath(paths[index], fill)
                drawPath(paths[index], colors.cell, style = Stroke((if (compact) 1.dp else 2.dp).toPx(), join = StrokeJoin.Round))
            }
            // outlines are drawn after all zones so a neighbor's fill doesn't cover them
            if (!compact) {
                val highlighted = venue.zones.indexOfFirst { it.id == highlightZone && it.id != myZone }
                if (highlighted >= 0) {
                    drawPath(paths[highlighted], colors.zoneInk(highlighted, highlightZone), style = Stroke(2.dp.toPx(), join = StrokeJoin.Round))
                }
            }
            val mine = venue.zones.indexOfFirst { it.id == myZone }
            if (mine >= 0) drawPath(paths[mine], colors.accent, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))

            if (!compact) {
                venue.zones.forEachIndexed { index, zone ->
                    val xs = zone.polygon.map { projection.project(it).x }
                    val width = (xs.max() - xs.min() - 8.dp.toPx()).toInt().coerceAtLeast(1)
                    // shrink the name until it fits the zone instead of truncating it ("Punct medi...")
                    fun label(size: TextUnit) = measurer.measure(
                        zone.name, labelStyle.copy(color = colors.zoneInk(index, zone.id), fontSize = size, lineHeight = size * 1.15f),
                        overflow = TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = width),
                    )
                    var size = labelStyle.fontSize
                    var text = label(size)
                    while (text.hasVisualOverflow && size > MinLabelSize) {
                        size = (size.value - 1).sp
                        text = label(size)
                    }
                    val center = projection.project(zone.center)
                    val mark = marks.firstOrNull { (it - center).getDistance() < 30.dp.toPx() }
                    val ys = zone.polygon.map { projection.project(it).y }
                    val gap = 14.dp.toPx()
                    val edge = 3.dp.toPx()
                    // keep the name inside the zone: above the marker, below if it doesn't fit, else against the top edge
                    val top = when {
                        mark == null -> center.y - text.size.height / 2f
                        mark.y - gap - text.size.height >= ys.min() + edge -> mark.y - gap - text.size.height
                        mark.y + gap + text.size.height <= ys.max() - edge -> mark.y + gap
                        else -> ys.min() + edge
                    }
                    drawText(text, topLeft = Offset(center.x - text.size.width / 2f, top))
                }
            }

            meeting?.let { o ->
                val r = (if (meetingFocused) 12.dp else 9.dp).toPx()
                if (meetingFocused) drawCircle(colors.orange.copy(alpha = 0.18f), r + 12.dp.toPx(), o)
                markerDot(o, r, colors.orange)
                val glyph = r * 1.2f
                translate(o.x - glyph / 2, o.y - glyph / 2) {
                    with(flag) { draw(Size(glyph, glyph), colorFilter = ColorFilter.tint(Color.White)) }
                }
            }

            spots.forEachIndexed { i, o -> markerDot(o, 7.dp.toPx(), pins[i].color) }

            marker?.let { o ->
                drawCircle(colors.accent.copy(alpha = 0.16f), 22.dp.toPx(), o)
                markerDot(o, 7.dp.toPx(), colors.accent)
            }
        }
    }
}

/** Marker: a colored dot in a 3 px white ring, with a soft shadow slightly below. */
private fun DrawScope.markerDot(center: Offset, radius: Float, color: Color) {
    val outer = radius + 3.dp.toPx()
    val spread = 3.dp.toPx()
    val shade = center + Offset(0f, 1.dp.toPx())
    drawCircle(
        Brush.radialGradient(0f to MarkerShadow, outer / (outer + spread) to MarkerShadow, 1f to Color.Transparent, center = shade, radius = outer + spread),
        outer + spread, shade,
    )
    drawCircle(Color.White, outer, center)
    drawCircle(color, radius, center)
}

fun incidentPoint(venue: Venue, incident: StaffIncident): GeoPoint? {
    val lat = incident.lat
    val lon = incident.lon
    if (lat != null && lon != null) return GeoPoint(lat, lon)
    return venue.zone(incident.zone)?.center
}
