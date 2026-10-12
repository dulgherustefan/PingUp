package ro.safetyplease.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.designsystem.AppColors
import ro.safetyplease.app.ui.designsystem.AppTheme
import ro.safetyplease.app.ui.designsystem.LocalReduceMotion
import ro.safetyplease.app.ui.designsystem.LocalTextOnly
import ro.safetyplease.app.ui.designsystem.Sym
import ro.safetyplease.app.ui.designsystem.caption1
import ro.safetyplease.app.ui.designsystem.caption2
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** [severity] is set on incident pins, so TalkBack can say how urgent they are. */
class MapPin(val point: GeoPoint, val color: Color, val key: String = "", val severity: Int? = null)

private val MapInset = 12.dp
private val CompactInset = 8.dp
private const val MaxZoom = 4f

/** Soft shadow under markers so the white ring stays visible on light zones. */
private val MarkerShadow = Color(0x29000000)

/** Equirectangular projection over the venue bounds; the error is negligible at festival scale. */
internal class Projection(venue: Venue, size: Size, pad: Float) {
    private val b = venue.bounds
    private val lonScale = cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    private val worldW = (b.maxLon - b.minLon) * lonScale
    private val worldH = b.maxLat - b.minLat
    // keep zones off the map edge
    private val scale = min((size.width - 2 * pad) / worldW, (size.height - 2 * pad) / worldH)
    private val offX = (size.width - worldW * scale) / 2
    private val offY = (size.height - worldH * scale) / 2

    /** Pixels per meter at zoom 1. */
    val pxPerMeter = (scale / 111_320.0).toFloat()

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

/** Map zoom and pan: a point at p on the whole map lands on screen at p * scale + offset. */
@Stable
class MapCamera {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var viewport = Size.Zero
    private var projection by mutableStateOf<Projection?>(null)

    /** The map has been laid out and knows where every point is. */
    val ready: Boolean get() = projection != null

    internal fun attach(projection: Projection, viewport: Size) {
        this.viewport = viewport
        this.projection = projection
        offset = clamp(offset, scale)
    }

    fun toScreen(base: Offset) = base * scale + offset

    fun toBase(screen: Offset) = (screen - offset) / scale

    fun transform(zoom: Float, pan: Offset, focal: Offset) {
        val next = (scale * zoom).coerceIn(1f, MaxZoom)
        offset = clamp(focal - (focal - offset) * (next / scale) + pan, next)
        scale = next
    }

    /** Fits all [points] as close as possible, at most at zoom [most]; with no points, the whole map. */
    suspend fun show(points: List<GeoPoint>, most: Float, animated: Boolean) {
        val p = projection ?: return
        var toScale = 1f
        var toOffset = Offset.Zero
        if (points.isNotEmpty()) {
            val base = points.map(p::project)
            val box = Rect(base.minOf { it.x }, base.minOf { it.y }, base.maxOf { it.x }, base.maxOf { it.y })
            // enough margin that the markers don't end up under the buttons
            val margin = min(viewport.width, viewport.height) * 0.24f
            toScale = if (box.width < 1f && box.height < 1f) most.coerceIn(1f, MaxZoom)
            else min(viewport.width / (box.width + 2 * margin), viewport.height / (box.height + 2 * margin)).coerceIn(1f, most)
            toOffset = clamp(Offset(viewport.width / 2, viewport.height / 2) - box.center * toScale, toScale)
        }
        if (!animated) {
            scale = toScale
            offset = toOffset
            return
        }
        val fromScale = scale
        val fromOffset = offset
        animate(0f, 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
            scale = fromScale + (toScale - fromScale) * t
            offset = fromOffset + (toOffset - fromOffset) * t
        }
    }

    /** The + and - buttons: zoom around the middle of the map. */
    suspend fun zoomBy(factor: Float, animated: Boolean) {
        val p = projection ?: return
        val next = (scale * factor).coerceIn(1f, MaxZoom)
        val middle = p.unproject(toBase(Offset(viewport.width / 2, viewport.height / 2)))
        show(if (next <= 1f) emptyList() else listOf(middle), next, animated)
    }

    /** Double tap: zoom in around the finger; from close up, back to the whole map. */
    suspend fun zoomAround(screen: Offset, animated: Boolean) {
        val p = projection ?: return
        if (scale >= MaxZoom * 0.9f) show(emptyList(), 1f, animated)
        else show(listOf(p.unproject(toBase(screen))), scale * 2f, animated)
    }

    private fun clamp(o: Offset, s: Float) = Offset(
        o.x.coerceIn(viewport.width * (1 - s), 0f),
        o.y.coerceIn(viewport.height * (1 - s), 0f),
    )
}

/** Terrain: grass around, packed earth inside the fence, lighter walkways, a dashed fence. */
private class MapPaint(colors: AppColors) {
    val grass = if (colors.dark) Color(0xFF111813) else Color(0xFFDCE9D3)
    val ground = if (colors.dark) Color(0xFF1A221C) else Color(0xFFF3F1E7)
    val path = if (colors.dark) Color(0xFF2B352D) else Color.White
    val pathEdge = if (colors.dark) Color(0xFF232C25) else Color(0xFFE3DFD0)
    val fence = if (colors.dark) Color(0xFF46534A) else Color(0xFFB4AE98)
    val shadow = Color.Black.copy(alpha = if (colors.dark) 0.35f else 0.10f)
    val halo = if (colors.dark) Color(0xD9111813) else Color(0xE6FFFFFF)
}

private val GlyphTypes = listOf("stage", "food", "bar", "medical", "entrance", "exit", "camping", "chill", "water", "wc", "info", "charge", Venue.DEA)

@Composable
private fun rememberGlyphs(): Map<String, Painter> = GlyphTypes.associateWith { rememberVectorPainter(typeGlyph(it)!!) }

/**
 * Venue map drawn on a Canvas, no tiles, no internet: the fenced site with its walkways, zones colored by type with
 * what's in them (stage and crowd, tents, trees, stalls), their icon and name, the useful points, a compass and a scale bar.
 * You are the pulsing dot labeled "Tu"; [target] is where you're heading, joined to you by a dotted route.
 * With [camera] the map zooms with two fingers, a double tap and the buttons. [compact] is the message bubble variant.
 * [reserved] are the corners the overlay's controls cover: no name or scale bar is drawn under them.
 * [grid] draws the site plan's squares, lettered along the top and numbered down the left side; [squareShades] fill
 * some of them, by 0-based column and row. [poiRings] ring numbered points in a color, by their number.
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
    camera: MapCamera? = null,
    target: GeoPoint? = null,
    targetColor: Color = AppTheme.colors.accent,
    grid: SiteGrid? = null,
    squareShades: Map<Pair<Int, Int>, Color> = emptyMap(),
    poiRings: Map<Int, Color> = emptyMap(),
    aspect: Float = venueAspect(venue),
    reserved: List<MapReserve> = emptyList(),
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    if (LocalTextOnly.current) {
        TextOnlyMapNote(modifier)
        return
    }
    val colors = AppTheme.colors
    val paint = remember(colors) { MapPaint(colors) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val smallStyle = MaterialTheme.typography.caption2.copy(fontWeight = FontWeight.Bold)
    val glyphs = rememberGlyphs()
    val flag = rememberVectorPainter(Sym.Flag)
    val resources = LocalResources.current
    val density = LocalDensity.current
    val youLabel = stringResource(R.string.map_you)
    val north = stringResource(R.string.map_north)
    val currentPins by rememberUpdatedState(pins)
    val currentTap by rememberUpdatedState(onTap)
    val currentPinTap by rememberUpdatedState(onPinTap)
    val animateMoves by rememberUpdatedState(!LocalReduceMotion.current)
    val scope = rememberCoroutineScope()
    val inset = if (compact) CompactInset else MapInset
    val words = rememberMapWords()
    val nodes = if (compact) emptyList() else remember(venue, words, position, myZone, pins, onPinTap != null) {
        mapNodes(venue, words, position, myZone, if (onPinTap != null) pins else emptyList())
    }
    val summary = pluralStringResource(R.plurals.map_a11y_summary, venue.zones.size, venue.zones.size)
    val pulse: State<Float>? = if (!compact && animateMoves && (position != null || target != null)) {
        rememberInfiniteTransition(label = "map").animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "pulse")
    } else null

    Box(
        modifier.fillMaxWidth().aspectRatio(aspect)
            .then(if (compact) Modifier else Modifier.clip(RoundedCornerShape(26.dp)))
            // in a bubble the background is translucent so the bubble color shows through
            .background(if (compact) colors.fill else paint.grass)
            .then(if (compact) Modifier else Modifier.semantics { isTraversalGroup = true }),
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .then(
                    if (compact) Modifier else Modifier.semantics {
                        contentDescription = summary
                        traversalIndex = -1f
                    },
                )
                .onSizeChanged { size -> camera?.attach(Projection(venue, size.toSize(), with(density) { inset.toPx() }), size.toSize()) }
                .then(
                    if (camera == null) Modifier else Modifier.pointerInput(camera) {
                        val slop = viewConfiguration.touchSlop
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var moved = 0f
                            var active = false
                            do {
                                val event = awaitPointerEvent()
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (!active) {
                                    moved += pan.getDistance()
                                    // one finger on the whole map lets the screen scroll
                                    active = event.changes.count { it.pressed } > 1 || (camera.scale > 1.01f && moved > slop)
                                }
                                if (active && (zoom != 1f || pan != Offset.Zero)) {
                                    camera.transform(zoom, pan, event.calculateCentroid())
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    },
                )
                .then(
                    if (onTap == null && onPinTap == null && camera == null) Modifier else Modifier.pointerInput(venue, inset, camera) {
                        detectTapGestures(
                            onDoubleTap = if (camera == null) null else { o -> scope.launch { camera.zoomAround(o, animateMoves) } },
                            onTap = { offset ->
                                val projection = Projection(venue, Size(size.width.toFloat(), size.height.toFloat()), inset.toPx())
                                val screen = { p: GeoPoint -> camera?.toScreen(projection.project(p)) ?: projection.project(p) }
                                val hit = currentPins.minByOrNull { (screen(it.point) - offset).getDistance() }
                                if (hit != null && currentPinTap != null && (screen(hit.point) - offset).getDistance() < 28.dp.toPx()) {
                                    currentPinTap?.invoke(hit)
                                } else {
                                    val point = projection.unproject(camera?.toBase(offset) ?: offset)
                                    if (venue.bounds.contains(point.lat, point.lon)) currentTap?.invoke(point, venue.zoneAt(point.lat, point.lon))
                                }
                            },
                        )
                    },
                ),
        ) {
            val projection = Projection(venue, size, inset.toPx())
            val zoom = camera?.scale ?: 1f
            val shift = camera?.offset ?: Offset.Zero
            fun at(p: GeoPoint) = projection.project(p) * zoom + shift
            val meter = projection.pxPerMeter * zoom
            val marker = position?.let(::at)
            val spots = pins.map { at(it.point) }
            val corner = 7.dp.toPx() * min(zoom, 2f)
            val zonePaths = venue.zones.map { zone -> roundedShape(zone.polygon.map(::at), corner) }

            if (compact) {
                venue.zones.forEachIndexed { index, zone ->
                    drawPath(zonePaths[index], if (zone.id == highlightZone) colors.zoneFill(zoneTone(venue, index), zone.id) else colors.cell)
                    drawPath(zonePaths[index], colors.fill, style = Stroke(1.dp.toPx(), join = StrokeJoin.Round))
                }
                val mine = venue.zones.indexOfFirst { it.id == myZone }
                if (mine >= 0) drawPath(zonePaths[mine], colors.accent, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
                spots.forEachIndexed { i, o -> markerDot(o, 7.dp.toPx(), pins[i].color) }
                marker?.let { o ->
                    drawCircle(colors.accent.copy(alpha = 0.16f), 22.dp.toPx(), o)
                    markerDot(o, 7.dp.toPx(), colors.accent)
                }
                return@Canvas
            }

            val b = venue.bounds
            val nw = at(GeoPoint(b.maxLat, b.minLon))
            val se = at(GeoPoint(b.minLat, b.maxLon))
            val site = Size(se.x - nw.x, se.y - nw.y)
            val siteCorner = CornerRadius(14.dp.toPx() * zoom)
            drawRoundRect(paint.ground, nw, site, siteCorner)
            drawRoundRect(
                paint.fence, nw, site, siteCorner,
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
            )

            // walkways go under the zones and show in the gaps between them
            val alley = max(3.dp.toPx(), 7f * meter)
            venue.paths.forEach { line ->
                val path = openShape(line.map(::at))
                drawPath(path, paint.pathEdge, style = Stroke(alley + 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, paint.path, style = Stroke(alley, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            venue.zones.forEachIndexed { index, zone ->
                val tone = zoneTone(venue, index)
                val path = zonePaths[index]
                val box = path.getBounds()
                val solid = colors.zoneSolid(tone, zone.id)
                val fill = colors.zoneFill(tone, zone.id)
                translate(0f, 2.dp.toPx()) { drawPath(path, paint.shadow) }
                val top = if (colors.dark) solid.copy(alpha = 0.48f).compositeOver(paint.ground) else fill
                val bottom = if (colors.dark) solid.copy(alpha = 0.30f).compositeOver(paint.ground) else solid.copy(alpha = 0.14f).compositeOver(fill)
                drawPath(path, Brush.verticalGradient(listOf(top, bottom), startY = box.top, endY = box.bottom))
                val ink = if (colors.dark) colors.zoneInk(tone, zone.id).copy(alpha = 0.32f) else solid.copy(alpha = 0.24f)
                clipPath(path) { zoneDetails(zone.type, box, ink, meter, index) }
                val edge = if (colors.dark) colors.zoneInk(tone, zone.id).copy(alpha = 0.5f) else solid.copy(alpha = 0.4f)
                drawPath(path, edge, style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            }
            // outlines come after all zones so a neighbor's fill doesn't cover them
            val highlighted = venue.zones.indexOfFirst { it.id == highlightZone && it.id != myZone }
            if (highlighted >= 0) {
                val tone = zoneTone(venue, highlighted)
                val outline = if (colors.dark) colors.zoneInk(tone, highlightZone) else colors.zoneSolid(tone, highlightZone)
                drawPath(zonePaths[highlighted], outline, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
            }
            val mine = venue.zones.indexOfFirst { it.id == myZone }
            if (mine >= 0) {
                drawPath(zonePaths[mine], colors.accent.copy(alpha = 0.28f), style = Stroke(9.dp.toPx(), join = StrokeJoin.Round))
                drawPath(zonePaths[mine], colors.accent, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
            }

            grid?.let { g ->
                for ((square, shade) in squareShades) {
                    val (column, row) = square
                    if (column >= g.columns || row >= g.rows) continue
                    val from = at(GeoPoint(g.rowLat(row), g.columnLon(column)))
                    val to = at(GeoPoint(g.rowLat(row + 1), g.columnLon(column + 1)))
                    drawRect(shade, from, Size(to.x - from.x, to.y - from.y))
                }
                val line = colors.label.copy(alpha = if (colors.dark) 0.18f else 0.14f)
                for (column in 1 until g.columns) {
                    val x = at(GeoPoint(b.maxLat, g.columnLon(column))).x
                    drawLine(line, Offset(x, nw.y), Offset(x, se.y), 1.dp.toPx())
                }
                for (row in 1 until g.rows) {
                    val y = at(GeoPoint(g.rowLat(row), b.minLon)).y
                    drawLine(line, Offset(nw.x, y), Offset(se.x, y), 1.dp.toPx())
                }
            }

            // the route to the target, as a dotted walking line
            val goal = target?.let(::at)
            if (marker != null && goal != null) {
                val dots = PathEffect.dashPathEffect(floatArrayOf(0.1f, 9.dp.toPx()))
                drawLine(Color.White, marker, goal, strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round, pathEffect = dots)
                drawLine(targetColor, marker, goal, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dots)
            }

            venue.pois.forEach { poi ->
                val o = at(poi.point)
                val focused = goal != null && (goal - o).getDistance() < 1f
                poiRings[poi.number]?.takeIf { poi.number > 0 }?.let { ring ->
                    drawCircle(ring, (if (focused) 17.dp else 13.dp).toPx(), o)
                }
                badge(o, (if (focused) 12.dp else 8.dp).toPx(), spotColor(colors, poi.type), glyphs[poi.type])
            }

            val meeting = venue.meetingPoint?.let(::at)
            meeting?.let { o ->
                val big = meetingFocused || (goal != null && (goal - o).getDistance() < 1f)
                val r = (if (big) 12.dp else 9.dp).toPx()
                markerDot(o, r, colors.orange)
                val g = r * 1.2f
                translate(o.x - g / 2, o.y - g / 2) { with(flag) { draw(Size(g, g), colorFilter = ColorFilter.tint(Color.White)) } }
            }

            spots.forEachIndexed { i, o -> markerDot(o, 7.dp.toPx(), pins[i].color) }

            val compass = Offset(size.width - 22.dp.toPx(), 22.dp.toPx())
            val compassRadius = 13.dp.toPx()

            val steps = intArrayOf(5, 10, 20, 25, 50, 100, 200, 250, 500)
            val meters = steps.firstOrNull { it * meter >= 44.dp.toPx() } ?: steps.last()
            val barLength = meters * meter
            val scaleText = measurer.measure(resources.getString(R.string.map_meters, meters), smallStyle.copy(color = colors.label))
            val tick = 5.dp.toPx()
            val textRise = 7.dp.toPx() + scaleText.size.height
            val barWidth = max(barLength, scaleText.size.width + 2.dp.toPx())
            val compassBox = Rect(compass - Offset(compassRadius + 4.dp.toPx(), compassRadius + 4.dp.toPx()), Size(2 * compassRadius + 8.dp.toPx(), 2 * compassRadius + 8.dp.toPx()))
            val corners = reserved.map { cornerRect(it.corner, size, it.width.toPx(), it.height.toPx()) } + compassBox
            // the scale bar takes the first corner no control covers; its line is 3 dp thick and hangs below the baseline
            val bar = firstFree(
                listOf(MapCorner.BottomStart, MapCorner.BottomEnd, MapCorner.TopStart)
                    .map { cornerRect(it, size, barWidth, textRise + 3.dp.toPx(), 16.dp.toPx(), 11.dp.toPx()) },
                corners,
            )

            // each zone's icon and name: stacked, side by side or just the icon, depending on the room; names never end
            // under a control, the scale bar or a marker, and never leave their zone or the screen. The target doesn't
            // move them, its ring goes around the zone's own icon
            val avoid = corners + bar.inflate(3.dp.toPx()) +
                listOfNotNull(marker?.let { Rect(it.x - 22.dp.toPx(), it.y - 48.dp.toPx(), it.x + 22.dp.toPx(), it.y + 16.dp.toPx()) }) +
                (listOfNotNull(meeting) + spots).map { Rect(it.x - 12.dp.toPx(), it.y - 12.dp.toPx(), it.x + 12.dp.toPx(), it.y + 12.dp.toPx()) }
            val screen = Rect(Offset.Zero, size)
            venue.zones.forEachIndexed { index, zone ->
                val solid = colors.zoneSolid(zoneTone(venue, index), zone.id)
                val box = zonePaths[index].getBounds().intersect(screen)
                val room = Size(box.width - 8.dp.toPx(), box.height - 6.dp.toPx())
                if (room.width < 12.dp.toPx() || room.height < 12.dp.toPx()) return@forEachIndexed
                val glyph = glyphs[zone.type]
                val r = min(10.dp.toPx(), room.height / 2)
                val d = if (glyph != null) 2 * r else 0f
                val gap = if (glyph != null) 3.dp.toPx() else 0f
                val ink = if (colors.dark) colors.label else solid
                val style = labelStyle.copy(color = ink)
                fun name(width: Float, align: TextAlign) = measurer.measure(
                    zone.name, style.copy(textAlign = align), TextOverflow.Ellipsis, maxLines = 2,
                    constraints = Constraints(maxWidth = max(1f, width).toInt()),
                )
                val stacked = name(room.width, TextAlign.Center)
                val beside = name(room.width - d - gap, TextAlign.Start)
                fun measured(l: TextLayoutResult) = MeasuredName(
                    l.size.width.toFloat(), l.size.height.toFloat(),
                    !l.hasVisualOverflow && !breaksWord(zone.name, List(l.lineCount) { l.getLineEnd(it) }),
                )
                var mode = chooseZoneLabel(room.width, room.height, d, gap, ZoneLabelMinWidth.toPx(), measured(stacked), measured(beside))
                if (mode == ZoneLabelMode.None) return@forEachIndexed
                fun groupOf(m: ZoneLabelMode) = when (m) {
                    ZoneLabelMode.Stacked -> Size(max(d, stacked.size.width.toFloat()), d + gap + stacked.size.height)
                    ZoneLabelMode.Beside -> Size(d + gap + beside.size.width, max(d, beside.size.height.toFloat()))
                    ZoneLabelMode.IconOnly -> Size(d, d)
                    ZoneLabelMode.TextOnly -> Size(stacked.size.width.toFloat(), stacked.size.height.toFloat())
                    ZoneLabelMode.None -> Size.Zero
                }
                val within = Rect(box.left + 4.dp.toPx(), box.top + 3.dp.toPx(), box.right - 4.dp.toPx(), box.bottom - 3.dp.toPx())
                val center = at(zone.center)
                fun spot(m: ZoneLabelMode) = groupOf(m).let { g -> placeClear(g, center - Offset(g.width / 2, g.height / 2), within, avoid) }
                var origin = spot(mode)
                if (origin == null && mode != ZoneLabelMode.IconOnly && glyph != null) {
                    mode = ZoneLabelMode.IconOnly
                    origin = spot(mode)
                }
                if (origin == null) return@forEachIndexed
                val group = groupOf(mode)
                when (mode) {
                    ZoneLabelMode.Stacked -> {
                        badge(Offset(origin.x + group.width / 2, origin.y + r), r, solid, glyph, ring = 1.5.dp.toPx())
                        haloText(stacked, Offset(origin.x + (group.width - stacked.size.width) / 2f, origin.y + d + gap), ink, paint.halo)
                    }
                    ZoneLabelMode.Beside -> {
                        badge(Offset(origin.x + r, origin.y + group.height / 2), r, solid, glyph, ring = 1.5.dp.toPx())
                        haloText(beside, Offset(origin.x + d + gap, origin.y + (group.height - beside.size.height) / 2f), ink, paint.halo)
                    }
                    ZoneLabelMode.IconOnly -> badge(Offset(origin.x + r, origin.y + r), r, solid, glyph, ring = 1.5.dp.toPx())
                    ZoneLabelMode.TextOnly -> haloText(stacked, origin, ink, paint.halo)
                    ZoneLabelMode.None -> Unit
                }
            }

            marker?.let { o ->
                pulse?.value?.let { p -> drawCircle(colors.accent.copy(alpha = 0.32f * (1 - p)), 9.dp.toPx() + 26.dp.toPx() * p, o) }
                drawCircle(colors.accent.copy(alpha = 0.18f), 17.dp.toPx(), o)
                markerDot(o, 8.dp.toPx(), colors.accent)
                val text = measurer.measure(youLabel, smallStyle.copy(color = colors.onAccent))
                val w = text.size.width + 14.dp.toPx()
                val h = text.size.height + 4.dp.toPx()
                val tl = Offset(o.x - w / 2, o.y - 13.dp.toPx() - 5.dp.toPx() - h)
                drawRoundRect(MarkerShadow, tl + Offset(0f, 1.dp.toPx()), Size(w, h), CornerRadius(h / 2))
                drawRoundRect(colors.accent, tl, Size(w, h), CornerRadius(h / 2))
                val tip = Path().apply {
                    moveTo(o.x - 5.dp.toPx(), tl.y + h - 1f)
                    lineTo(o.x + 5.dp.toPx(), tl.y + h - 1f)
                    lineTo(o.x, tl.y + h + 5.dp.toPx())
                    close()
                }
                drawPath(tip, colors.accent)
                drawText(text, topLeft = Offset(o.x - text.size.width / 2f, tl.y + 2.dp.toPx()))
            }
            goal?.let { o ->
                val p = pulse?.value ?: 0.4f
                drawCircle(targetColor.copy(alpha = 0.7f * (1 - p)), 15.dp.toPx() + 14.dp.toPx() * p, o, style = Stroke(2.5.dp.toPx()))
            }

            // grid labels stay on the visible edges of the site while the map is zoomed in
            grid?.let { g ->
                val style = smallStyle.copy(color = colors.secondaryLabel)
                val top = max(nw.y, 0f) + 3.dp.toPx()
                val left = max(nw.x, 0f) + 4.dp.toPx()
                for (column in 0 until g.columns) {
                    val text = measurer.measure(g.columnLabel(column), style)
                    val x = (at(GeoPoint(b.maxLat, g.columnLon(column))).x + at(GeoPoint(b.maxLat, g.columnLon(column + 1))).x) / 2
                    haloText(text, Offset(x - text.size.width / 2f, top), colors.secondaryLabel, paint.halo)
                }
                for (row in 0 until g.rows) {
                    val text = measurer.measure(g.rowLabel(row), style)
                    val y = (at(GeoPoint(g.rowLat(row), b.minLon)).y + at(GeoPoint(g.rowLat(row + 1), b.minLon)).y) / 2
                    haloText(text, Offset(left, y - text.size.height / 2f), colors.secondaryLabel, paint.halo)
                }
            }

            // compass top right, scale bar in the corner chosen above
            val c = compass
            val cr = compassRadius
            drawCircle(MarkerShadow, cr + 1.dp.toPx(), c + Offset(0f, 1.dp.toPx()))
            drawCircle(colors.cell, cr, c)
            val needle = Path().apply {
                moveTo(c.x, c.y - cr + 2.dp.toPx())
                lineTo(c.x - 3.5.dp.toPx(), c.y - cr + 7.dp.toPx())
                lineTo(c.x + 3.5.dp.toPx(), c.y - cr + 7.dp.toPx())
                close()
            }
            drawPath(needle, colors.red)
            val n = measurer.measure(north, smallStyle.copy(color = colors.label))
            drawText(n, topLeft = Offset(c.x - n.size.width / 2f, c.y - n.size.height / 2f + 2.dp.toPx()))

            val x0 = bar.left
            val y0 = bar.top + textRise
            for ((color, width) in listOf(paint.halo to 5.dp.toPx(), colors.label to 2.dp.toPx())) {
                drawLine(color, Offset(x0, y0), Offset(x0 + barLength, y0), width, StrokeCap.Round)
                drawLine(color, Offset(x0, y0 - tick), Offset(x0, y0), width, StrokeCap.Round)
                drawLine(color, Offset(x0 + barLength, y0 - tick), Offset(x0 + barLength, y0), width, StrokeCap.Round)
            }
            haloText(scaleText, Offset(x0 + 2.dp.toPx(), y0 - 7.dp.toPx() - scaleText.size.height), colors.label, paint.halo)
        }
        if (!compact) {
            MapSemanticsLayer(venue, nodes, inset, camera, highlightZone, currentTap, currentPinTap, Modifier.matchParentSize())
        }
        // the map's own buttons come after everything on it
        Box(
            Modifier.matchParentSize().semantics {
                isTraversalGroup = true
                traversalIndex = 1f
            },
            content = overlay,
        )
    }
}

private fun openShape(points: List<Offset>) = Path().apply {
    points.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
}

/** The polygon with rounded corners, so zones don't look cut out with scissors. */
private fun roundedShape(points: List<Offset>, radius: Float) = Path().apply {
    val n = points.size
    for (i in 0 until n) {
        val prev = points[(i - 1 + n) % n]
        val cur = points[i]
        val next = points[(i + 1) % n]
        val inLen = (cur - prev).getDistance()
        val outLen = (next - cur).getDistance()
        if (inLen < 0.5f || outLen < 0.5f) continue
        val r = minOf(radius, inLen / 2, outLen / 2)
        val a = cur + (prev - cur) / inLen * r
        val c = cur + (next - cur) / outLen * r
        if (isEmpty) moveTo(a.x, a.y) else lineTo(a.x, a.y)
        quadraticTo(cur.x, cur.y, c.x, c.y)
    }
    close()
}

/** What's drawn inside a zone, sized in real meters: stage with speakers and crowd, tents, trees, stalls and tables, turnstiles. */
private fun DrawScope.zoneDetails(type: String, box: Rect, ink: Color, meter: Float, seed: Int) {
    val strong = ink.copy(alpha = min(1f, ink.alpha * 2.4f))
    when (type) {
        "stage" -> {
            val w = box.width * 0.5f
            val h = max(box.height * 0.16f, 4.dp.toPx())
            val tl = Offset(box.center.x - w / 2, box.top + box.height * 0.08f)
            drawRoundRect(strong, tl, Size(w, h), CornerRadius(3.dp.toPx()))
            val speaker = h * 0.75f
            drawRoundRect(strong, Offset(tl.x - speaker - 3.dp.toPx(), tl.y), Size(speaker, speaker), CornerRadius(2.dp.toPx()))
            drawRoundRect(strong, Offset(tl.x + w + 3.dp.toPx(), tl.y), Size(speaker, speaker), CornerRadius(2.dp.toPx()))
            val front = Offset(box.center.x, tl.y + h)
            for (f in floatArrayOf(0.3f, 0.5f, 0.7f, 0.9f)) {
                val r = box.height * f
                drawArc(ink, 35f, 110f, false, front - Offset(r, r), Size(2 * r, 2 * r), style = Stroke(1.2.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        "camping" -> {
            val step = max(16f * meter, 11.dp.toPx())
            val t = step * 0.42f
            var row = 0
            var y = box.top + step * 0.7f
            while (y < box.bottom) {
                var x = box.left + step * (if (row % 2 == 0) 0.5f else 1f)
                while (x < box.right) {
                    drawPath(Path().apply { moveTo(x, y - t); lineTo(x - t, y + t * 0.6f); lineTo(x + t, y + t * 0.6f); close() }, ink)
                    x += step
                }
                y += step * 0.85f
                row++
            }
        }
        "chill" -> {
            val step = max(14f * meter, 10.dp.toPx())
            var i = 0
            var y = box.top + step * 0.6f
            while (y < box.bottom) {
                var x = box.left + step * 0.5f
                while (x < box.right) {
                    val jitter = ((i * 37 + seed * 11) % 7) / 7f - 0.5f
                    drawCircle(ink, step * (0.24f + 0.08f * jitter), Offset(x + jitter * step * 0.5f, y - jitter * step * 0.3f))
                    x += step
                    i++
                }
                y += step * 0.9f
            }
        }
        "food" -> {
            val w = max(9f * meter, 8.dp.toPx())
            val h = max(4.5f * meter, 4.dp.toPx())
            var x = box.left + w * 0.4f
            while (x + w < box.right) {
                drawRoundRect(strong, Offset(x, box.top + 4.dp.toPx()), Size(w, h), CornerRadius(1.5.dp.toPx()))
                x += w * 1.45f
            }
            tables(box.copy(top = box.top + 4.dp.toPx() + h + 6.dp.toPx()), ink, max(11f * meter, 9.dp.toPx()))
        }
        "bar" -> {
            val h = max(3.5f * meter, 3.dp.toPx())
            drawRoundRect(strong, Offset(box.left + box.width * 0.12f, box.top + box.height * 0.14f), Size(box.width * 0.76f, h), CornerRadius(h / 2))
            tables(box.copy(top = box.top + box.height * 0.14f + h + 6.dp.toPx()), ink, max(10f * meter, 8.dp.toPx()))
        }
        "entrance" -> {
            val step = max(4.5f * meter, 6.dp.toPx())
            val len = max(6f * meter, 6.dp.toPx())
            val y = box.bottom - box.height * 0.22f
            var x = box.left + step
            while (x < box.right - step * 0.5f) {
                drawLine(strong, Offset(x, y - len), Offset(x, y), 1.5.dp.toPx(), StrokeCap.Round)
                x += step
            }
        }
    }
}

private fun DrawScope.tables(area: Rect, ink: Color, step: Float) {
    var y = area.top + step * 0.5f
    while (y < area.bottom - step * 0.2f) {
        var x = area.left + step * 0.6f
        while (x < area.right - step * 0.3f) {
            drawCircle(ink, step * 0.17f, Offset(x, y))
            x += step
        }
        y += step
    }
}

/** Map text with a soft halo in the ground color, so it reads over any zone. */
private fun DrawScope.haloText(layout: TextLayoutResult, topLeft: Offset, color: Color, halo: Color) {
    val glow = Shadow(halo, Offset.Zero, 4.dp.toPx())
    // drawn twice: one blurred shadow is too faint to carry the text
    drawText(layout, color = color, topLeft = topLeft, shadow = glow)
    drawText(layout, color = color, topLeft = topLeft, shadow = glow)
}

private fun DrawScope.softShadow(center: Offset, outer: Float) {
    val spread = 3.dp.toPx()
    val shade = center + Offset(0f, 1.dp.toPx())
    drawCircle(
        Brush.radialGradient(0f to MarkerShadow, outer / (outer + spread) to MarkerShadow, 1f to Color.Transparent, center = shade, radius = outer + spread),
        outer + spread, shade,
    )
}

/** Marker: a colored dot in a 3 px white ring, with a soft shadow slightly below. */
private fun DrawScope.markerDot(center: Offset, radius: Float, color: Color) {
    val outer = radius + 3.dp.toPx()
    softShadow(center, outer)
    drawCircle(Color.White, outer, center)
    drawCircle(color, radius, center)
}

/** The colored circle with the white icon of a zone or a useful point. */
private fun DrawScope.badge(center: Offset, radius: Float, color: Color, glyph: Painter?, ring: Float = 2.dp.toPx()) {
    val outer = radius + ring
    softShadow(center, outer)
    drawCircle(Color.White, outer, center)
    drawCircle(color, radius, center)
    if (glyph != null) {
        val s = radius * 1.2f
        translate(center.x - s / 2, center.y - s / 2) { with(glyph) { draw(Size(s, s), colorFilter = ColorFilter.tint(Color.White)) } }
    }
}

fun incidentPoint(venue: Venue, incident: StaffIncident): GeoPoint? {
    val lat = incident.lat
    val lon = incident.lon
    if (lat != null && lon != null) return GeoPoint(lat, lon)
    return venue.zone(incident.zone)?.center
}
