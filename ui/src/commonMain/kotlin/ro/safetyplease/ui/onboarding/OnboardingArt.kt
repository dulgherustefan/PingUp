package ro.safetyplease.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.designsystem.AppColors
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Avatar
import ro.safetyplease.ui.designsystem.LocalReduceMotion
import ro.safetyplease.ui.designsystem.Motion
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.onboarding_no_signal

// The onboarding's two pictures, drawn as geometry: on the welcome, a message hopping from phone to phone up to a
// friend's; on the last page, rings around the person with a phone for each one found.

/** One frame of the welcome's picture. */
internal class HopFrame(
    /** The hop the message is on, from `path[hop]` to `path[hop + 1]`; [MeshHop.HOPS] once it has arrived. */
    val hop: Int,
    /** How far along that hop, eased: 0 on the phone it left, 1 on the next one. */
    val along: Float,
    /** Phones on the path the message has reached, the sender's included. */
    val reached: Int,
    /** How lit the path is: 1 while the message travels and arrives, down to 0 before the loop starts again. */
    val lit: Float,
    /** A ring spreading from each phone the message just reached: its place on the path, and how far, 0 to 1. */
    val rings: List<Pair<Int, Float>>,
)

/**
 * The welcome's picture: phones scattered over a field, a message leaving the first one, hopping through the phones
 * of three strangers and arriving on a friend's. Places are fractions of the picture.
 */
internal object MeshHop {
    const val HOPS = 4

    /** One hop, then the pause on the phone it reached. */
    const val TRAVEL = 620
    const val DWELL = 160

    /** The friend's phone lit, then everything dims before the next message. */
    const val ARRIVED = 1200
    const val DIM = 700
    const val RING = 760
    const val PERIOD = HOPS * (TRAVEL + DWELL) + ARRIVED + DIM

    /** One message, from leaving to lit on the friend's phone: the picture plays it once and stays there. */
    const val PLAY = HOPS * (TRAVEL + DWELL) + ARRIVED

    val nodes = listOf(
        Offset(0.08f, 0.62f), Offset(0.30f, 0.36f), Offset(0.50f, 0.68f), Offset(0.70f, 0.34f), Offset(0.92f, 0.58f),
        // phones off the way, which the message doesn't need
        Offset(0.17f, 0.12f), Offset(0.33f, 0.93f), Offset(0.67f, 0.92f), Offset(0.84f, 0.12f),
    )
    val path = listOf(0, 1, 2, 3, 4)
    val links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 1 to 5, 0 to 6, 2 to 6, 2 to 7, 4 to 7, 3 to 8, 4 to 8)

    /** The crowd further off: a dot for each phone, away from the phones and links above, and how near it is, 0 to 1. */
    val crowd = listOf(
        Offset(0.64f, 0.24f) to 0.7f, Offset(0.44f, 0.24f) to 0.41f, Offset(0.15f, 0.36f) to 0.76f, Offset(0.4f, 0.96f) to 0.38f,
        Offset(0.84f, 0.41f) to 0.44f, Offset(0.8f, 0.32f) to 0.73f, Offset(0.09f, 0.34f) to 0.79f, Offset(0.43f, 0.43f) to 0.73f,
        Offset(0.26f, 0.64f) to 0.69f, Offset(0.85f, 0.76f) to 0.54f, Offset(0.95f, 0.27f) to 0.62f, Offset(0.66f, 0.79f) to 0.72f,
        Offset(0.68f, 0.66f) to 0.73f, Offset(0.46f, 0.85f) to 0.96f, Offset(0.09f, 0.74f) to 0.77f, Offset(0.96f, 0.84f) to 0.53f,
        Offset(0.39f, 0.71f) to 0.36f, Offset(0.14f, 0.23f) to 0.85f,
    )

    fun frame(ms: Int): HopFrame {
        val t = ms.mod(PERIOD)
        val step = TRAVEL + DWELL
        val travel = HOPS * step
        val rings = (0..HOPS).mapNotNull { j ->
            val at = if (j == 0) 0 else (j - 1) * step + TRAVEL
            val spread = (t - at).toFloat() / RING
            if (spread in 0f..1f) j to spread else null
        }
        if (t >= travel) {
            val dim = (t - travel - ARRIVED).toFloat() / DIM
            return HopFrame(HOPS, 1f, HOPS + 1, (1f - dim).coerceIn(0f, 1f), rings)
        }
        val hop = t / step
        val into = t % step
        return if (into < TRAVEL) {
            HopFrame(hop, if (into == 0) 0f else EaseInOutCubic.transform(into.toFloat() / TRAVEL), hop + 1, 1f, rings)
        } else {
            HopFrame(hop, 1f, hop + 2, 1f, rings)
        }
    }

    /** With reduced motion: the message has arrived and stays. */
    val still = HopFrame(HOPS, 1f, HOPS + 1, 1f, emptyList())
}

/** Where a phone found nearby shows on the last page: its angle in degrees, clockwise from the right, and its ring. */
internal data class RadarSpot(val angle: Float, val ring: Int)

/** At most this many phones show; more than that look the same. */
internal const val RADAR_MAX = 8

// the first three on the inner ring, nearest the person, the next five on the outer one; none within 30° of
// another on its ring
private val Spots = listOf(
    RadarSpot(310f, 0), RadarSpot(70f, 0), RadarSpot(190f, 0),
    RadarSpot(10f, 1), RadarSpot(130f, 1), RadarSpot(250f, 1), RadarSpot(85f, 1), RadarSpot(300f, 1),
)

internal fun radarSpots(count: Int): List<RadarSpot> = Spots.take(count.coerceIn(0, RADAR_MAX))

/** The pictures' colors: what is lit is the pin green, the rest faint, in the sage of the logo (its forest green on white). */
private class ArtColors(colors: AppColors) {
    // on white the bright green is too pale for a line; the accent is the same green, darker
    val lit = if (colors.dark) colors.brand else colors.accent
    val idle = (if (colors.dark) colors.sage else colors.brandDeep).copy(alpha = 0.42f)
    val line = (if (colors.dark) colors.sage else colors.brandDeep).copy(alpha = 0.14f)
    val field = (if (colors.dark) colors.sage else colors.brandDeep).copy(alpha = if (colors.dark) 0.07f else 0.05f)
    val body = colors.background
}

/**
 * The welcome's picture. The message travels once, in under five seconds, and stays arrived: nothing on the page moves
 * for longer than that on its own. A tap sends another one. Still from the start when the phone asks for reduced motion.
 */
@Composable
internal fun MeshPicture(modifier: Modifier = Modifier) {
    val art = ArtColors(AppTheme.colors)
    val reduce = LocalReduceMotion.current
    val bubble = rememberVectorPainter(Sym.ChatFill)
    var plays by remember { mutableIntStateOf(0) }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(plays, reduce) {
        if (reduce) return@LaunchedEffect clock.snapTo(MeshHop.PLAY.toFloat())
        // again: the lit way dims, then a new message leaves, and it stops where the first one did
        val from = if (plays == 0) 0 else MeshHop.PLAY
        val to = from + if (plays == 0) MeshHop.PLAY else MeshHop.PERIOD
        clock.snapTo(from.toFloat())
        // the first one waits for the page to be on screen
        if (plays == 0) delay(FIRST_PLAY_DELAY)
        clock.animateTo(to.toFloat(), tween(to - from, easing = LinearEasing))
    }
    // the words under it say the same, for whoever can't see it
    Canvas(
        modifier.clearAndSetSemantics {}.pointerInput(reduce) {
            if (!reduce) detectTapGestures { if (!clock.isRunning) plays++ }
        },
    ) {
        drawMesh(if (reduce) MeshHop.still else MeshHop.frame(clock.value.roundToInt()), art, bubble)
    }
}

private const val FIRST_PLAY_DELAY = 500L

private fun DrawScope.drawMesh(frame: HopFrame, art: ArtColors, bubble: Painter) {
    // the field keeps its proportions on a wide screen
    val w = minOf(size.width, size.height * 1.6f)
    val left = (size.width - w) / 2
    fun at(spot: Offset) = Offset(left + spot.x * w, spot.y * size.height)
    fun at(node: Int) = at(MeshHop.nodes[node])
    val path = MeshHop.path
    val hairline = 1.5.dp.toPx()

    // the field, faintly lit from the middle, and the crowd on it
    val middle = Offset(left + w / 2, size.height * 0.55f)
    drawCircle(Brush.radialGradient(listOf(art.field, Color.Transparent), middle, w * 0.62f), w * 0.62f, middle)
    for ((spot, near) in MeshHop.crowd) {
        drawCircle(art.idle.copy(alpha = 0.12f + 0.2f * near), (1.6f + 1.4f * near).dp.toPx(), at(spot))
    }
    for ((a, b) in MeshHop.links) drawLine(art.line, at(a), at(b), hairline)
    // the way already travelled, and the stretch the message is on
    for (j in 0 until frame.hop) drawLine(art.lit.copy(alpha = 0.5f * frame.lit), at(path[j]), at(path[j + 1]), 2.dp.toPx(), StrokeCap.Round)
    val moving = frame.hop < MeshHop.HOPS
    val message = if (moving) lerp(at(path[frame.hop]), at(path[frame.hop + 1]), frame.along) else at(path.last())
    if (moving) drawLine(art.lit.copy(alpha = 0.7f), at(path[frame.hop]), message, 2.dp.toPx(), StrokeCap.Round)

    for ((j, spread) in frame.rings) {
        val big = j == 0 || j == MeshHop.HOPS
        val radius = lerp(10.dp.toPx(), (if (big) 46.dp else 32.dp).toPx(), EaseOutCubic.transform(spread))
        drawCircle(art.lit.copy(alpha = 0.55f * (1f - spread) * frame.lit), radius, at(path[j]), style = Stroke(hairline))
    }
    for (node in MeshHop.nodes.indices) {
        val onPath = path.indexOf(node)
        val lit = if (onPath in 0 until frame.reached) frame.lit else 0f
        phone(at(node), big = node == path.first() || node == path.last(), art, lit)
    }
    if (moving) {
        drawCircle(Brush.radialGradient(listOf(art.lit.copy(alpha = 0.45f), Color.Transparent), message, 16.dp.toPx()), 16.dp.toPx(), message)
        drawCircle(art.lit, 4.5.dp.toPx(), message)
    }

    // a speech bubble over the sender while the message leaves, and over the friend once it arrived
    val sent = if (frame.hop == 0) 1f - frame.along else 0f
    if (sent > 0f) speech(bubble, at(path.first()), art.lit, sent, 1f)
    if (!moving && frame.lit > 0f) {
        val pop = frame.rings.firstOrNull { it.first == MeshHop.HOPS }?.second?.let { EaseOutBack.transform((it * 2.5f).coerceAtMost(1f)) } ?: 1f
        speech(bubble, at(path.last()), art.lit, frame.lit, pop)
    }
}

/** A phone, upright: lit, its screen glows in the pin green. */
private fun DrawScope.phone(center: Offset, big: Boolean, art: ArtColors, lit: Float, grow: Float = 1f) {
    val w = (if (big) 17.dp else 13.dp).toPx() * grow
    val h = w * 1.75f
    val corner = CornerRadius(w * 0.28f)
    val topLeft = center - Offset(w / 2, h / 2)
    if (lit > 0f) {
        val glow = w * 2.3f
        drawCircle(Brush.radialGradient(listOf(art.lit.copy(alpha = 0.3f * lit), Color.Transparent), center, glow), glow, center)
    }
    drawRoundRect(art.body, topLeft, Size(w, h), corner)
    val edge = lerpColor(art.idle, art.lit, lit)
    drawRoundRect(edge, topLeft, Size(w, h), corner, style = Stroke(1.5.dp.toPx() * grow))
    val inset = w * 0.2f
    val screen = lerpColor(art.idle.copy(alpha = 0.16f), art.lit.copy(alpha = 0.85f), lit)
    drawRoundRect(screen, topLeft + Offset(inset, inset * 1.5f), Size(w - 2 * inset, h - inset * 3f), CornerRadius(corner.x * 0.45f))
}

private fun DrawScope.speech(bubble: Painter, over: Offset, color: Color, alpha: Float, grow: Float) {
    val s = 18.dp.toPx()
    val pivot = over - Offset(0f, 34.dp.toPx())
    scale(grow, pivot) {
        translate(pivot.x - s / 2, pivot.y - s / 2) {
            with(bubble) { draw(Size(s, s), alpha.coerceIn(0f, 1f), ColorFilter.tint(color)) }
        }
    }
}

private fun lerpColor(from: Color, to: Color, f: Float) = androidx.compose.ui.graphics.lerp(from, to, f.coerceIn(0f, 1f))

/** "No signal", as the phone's status bar would show it: four bars, crossed out. Part of the picture, so not read out. */
@Composable
internal fun NoSignalPill(modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier.clip(CircleShape).background(colors.fill).padding(horizontal = 12.dp, vertical = 6.dp).clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = colors.secondaryLabel
        Canvas(Modifier.size(15.dp)) {
            val bar = size.width / 6.2f
            for (i in 0 until 4) {
                val h = size.height * (0.3f + 0.233f * i)
                drawRoundRect(
                    ink.copy(alpha = 0.55f), Offset(i * bar * 1.6f, size.height - h), Size(bar, h), CornerRadius(bar / 2),
                )
            }
            drawLine(ink, Offset(0f, size.height * 0.08f), Offset(size.width, size.height * 0.92f), 1.6.dp.toPx(), StrokeCap.Round)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(Res.string.onboarding_no_signal), style = MaterialTheme.typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = ink, maxLines = 1,
        )
    }
}

// a ring leaves the avatar every 800 ms, three in all, each spreading for 2.4 s
private const val RING_EVERY = 800
private const val RING_RINGS = 3
private const val RING_SPREAD = 2400

/** How long one burst of rings lasts: under five seconds, so nothing loops. */
internal const val RADAR_BURST = (RING_RINGS - 1) * RING_EVERY + RING_SPREAD

/** The rings in flight [ms] into a burst, each as how far it has spread, 0 to 1; none once the burst is over. */
internal fun radarRings(ms: Int): List<Float> = (0 until RING_RINGS).mapNotNull { i ->
    val spread = (ms - i * RING_EVERY).toFloat() / RING_SPREAD
    spread.takeIf { it >= 0f && it < 1f }
}

/**
 * The last page's picture: the person's avatar, a burst of rings spreading from it when the phone starts looking for
 * others and again each time the count changes, and a lit phone on the rings for each one found, popping in one
 * after the other.
 */
@Composable
internal fun RadarPicture(name: String, found: Int, searching: Boolean, modifier: Modifier = Modifier) {
    val art = ArtColors(AppTheme.colors)
    val reduce = LocalReduceMotion.current
    val burst = remember { Animatable(RADAR_BURST.toFloat()) }
    LaunchedEffect(found, searching, reduce) {
        if (!searching || reduce) return@LaunchedEffect burst.snapTo(RADAR_BURST.toFloat())
        burst.snapTo(0f)
        burst.animateTo(RADAR_BURST.toFloat(), tween(RADAR_BURST, easing = LinearEasing))
    }
    val shown by animateFloatAsState(
        found.coerceIn(0, RADAR_MAX).toFloat(), if (reduce) snap() else tween(Motion.SLOW * 2, easing = Motion.Enter), label = "found",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
            val unit = size.minDimension / 2
            val rings = listOf(0.56f, 0.9f)
            for (r in rings) drawCircle(art.line, unit * r, center, style = Stroke(1.dp.toPx()))
            for (spread in radarRings(burst.value.roundToInt())) {
                val radius = lerp(unit * 0.36f, unit, EaseOutCubic.transform(spread))
                drawCircle(art.lit.copy(alpha = 0.32f * (1f - spread)), radius, center, style = Stroke(1.5.dp.toPx()))
            }
            radarSpots(RADAR_MAX).forEachIndexed { i, spot ->
                val grow = (shown - i).coerceIn(0f, 1f)
                if (grow > 0f) {
                    val angle = spot.angle * PI.toFloat() / 180f
                    val r = unit * rings[spot.ring]
                    phone(center + Offset(cos(angle) * r, sin(angle) * r), big = false, art, lit = 1f, grow = EaseOutBack.transform(grow))
                }
            }
        }
        Avatar(name.ifBlank { "?" }, 88.dp)
    }
}
