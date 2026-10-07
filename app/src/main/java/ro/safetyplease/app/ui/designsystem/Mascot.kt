package ro.safetyplease.app.ui.designsystem

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

// Drawn from the logo (512 SVG): the pin in its own coordinates, placed in the 512 tile like the app icon.
private const val PIN = "M124 196A132 132 0 1 1 388 196C388 286 318 352 274 410Q256 434 238 410C194 352 124 286 124 196Z"
private const val LEFT = "M256 196L-90 -4V700H256Z"
private const val CAP = "M256 196L-90 -4V-300H800L584 -33Z"
private const val SMILE = "M211.4 232A57.6 57.6 0 0 0 300.6 232"
private val RAYS = listOf(
    "M198.4 396H204.4L91.6 512H67.6Z", "M222.4 396H232.4L183.6 512H143.6Z", "M248.6 396H253.1L248.2 512H230.2Z",
    "M268.7 396H279.7L336.8 512H292.8Z", "M294.9 396H300.4L401.4 512H379.4Z", "M311.8 396H319.8L465.2 512H433.2Z",
)

private val Green = Color(0xFF30D158)
private val Forest = Color(0xFF2B5E45)
private val Sage = Color(0xFFD3D8B2)

private fun path(d: String): Path = PathParser().parsePathString(d).toPath()

private class PinPaths {
    val pin = path(PIN)
    val left = path(LEFT)
    val cap = path(CAP)
    val smile = path(SMILE)
    val rays = RAYS.map(::path)

    /** Pin thickness: the same outline shifted down twice, joined by the band between them. */
    val body: Path = run {
        val low = path(PIN).apply { translate(Offset(0f, 44f)) }
        val mid = path(PIN).apply { translate(Offset(0f, 22f)) }
        val band = Path().apply { addRect(Rect(124f, 196f, 388f, 240f)) }
        val both = Path().apply { op(low, mid, PathOperation.Union) }
        Path().apply { op(both, band, PathOperation.Union) }
    }
}

private val Paths by lazy { PinPaths() }

/** The pin, in its 512 coordinates (lens center at 256, 196). */
private fun DrawScope.drawPin() {
    val p = Paths
    clipPath(p.body) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF25A345), Color(0xFF17642A)), 196f, 468f), Offset(-200f, -200f), Size(1000f, 1000f))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF224936), Color(0xFF152D21)), 196f, 468f), Offset(-200f, -200f), Size(456f, 1000f))
    }
    clipPath(p.pin) {
        drawRect(Green, Offset(-200f, -200f), Size(900f, 900f))
        drawPath(p.left, Forest)
        drawPath(p.cap, Sage)
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.Transparent), 64f, 256f), Offset(-200f, -200f), Size(900f, 900f))
        drawPath(p.pin, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.7f), Color.Transparent), 64f, 216f), style = Stroke(12f))
    }
    val lens = Offset(256f, 196f)
    drawCircle(Color.Black.copy(alpha = 0.24f), 83f, lens)
    drawCircle(
        Brush.radialGradient(0f to Color(0xFF9FA2A0), 0.5f to Color(0xFF29302B), 1f to Color(0xFF171A18), center = Offset(234.4f, 163.6f), radius = 115.2f),
        72f, lens,
    )
    rotate(-28f, Offset(234f, 166f)) {
        drawOval(Color.White.copy(alpha = 0.42f), Offset(200f, 149f), Size(68f, 34f))
    }
    drawPath(p.smile, Color.White.copy(alpha = 0.22f), style = Stroke(6f, cap = StrokeCap.Round))
}

/** Places the pin in the tile: translate(256 232) scale(0.8) translate(-256 -265), as in the SVG. */
private fun DrawScope.inTile(lift: Float, block: DrawScope.() -> Unit) = withTransform({
    translate(256f, 232f + lift)
    scale(0.8f, 0.8f, Offset.Zero)
    translate(-256f, -265f)
}) { block() }

/** How far the pin floats above rest, in 512 units; zero when the phone asks for reduced motion. */
@Composable
private fun rememberFloat(): Float {
    if (LocalReduceMotion.current) return 0f
    val transition = rememberInfiniteTransition(label = "pin")
    val lift by transition.animateFloat(0f, -14f, infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), label = "lift")
    return lift
}

/**
 * The logo pin with a smiling lens, floating above its shadow. Used where the app talks about itself
 * (onboarding, empty lists, settings), not in the middle of a task.
 */
@Composable
fun PinMascot(size: Dp, modifier: Modifier = Modifier) {
    val lift = rememberFloat()
    // tile crop: pin and shadow, no background (264 x 360 of 512)
    Canvas(modifier.size(size * (264f / 360f), size)) {
        val k = this.size.height / 360f
        withTransform({ scale(k, k, Offset.Zero); translate(-124f, -62f) }) {
            val spread = 1f + lift / 120f
            drawOval(Color.Black.copy(alpha = 0.22f), Offset(256f - 92f * spread, 391f), Size(184f * spread, 26f))
            inTile(lift) { drawPin() }
        }
    }
}

/** The full app icon: night tile, rays under the pin, and the pin. */
@Composable
fun AppLogo(size: Dp, modifier: Modifier = Modifier) {
    val lift = rememberFloat()
    Box(modifier.size(size).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val k = this.size.width / 512f
            withTransform({ scale(k, k, Offset.Zero) }) {
                val tile = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, 512f, 512f, CornerRadius(116f)))
                }
                clipPath(tile) {
                    drawRect(
                        Brush.radialGradient(0f to Color(0xFF444645), 0.55f to Color(0xFF151816), 1f to Color(0xFF101311), center = Offset(256f, 200f), radius = 340f),
                        Offset.Zero, Size(512f, 512f),
                    )
                    val glow = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.4f)), 392f, 512f)
                    Paths.rays.forEach { drawPath(it, glow) }
                }
                drawOval(Color.Black.copy(alpha = 0.25f), Offset(164f, 391f), Size(184f, 26f))
                inTile(lift) { drawPin() }
            }
        }
    }
}
