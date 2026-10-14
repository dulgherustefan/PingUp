package ro.safetyplease.ui.designsystem

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import kotlin.math.abs

// Where the mascot and the logo go and how the pin floats; what they look like is in BrandArt.kt.

/** Places the pin in the tile: translate(256 232) scale(0.8) translate(-256 -265), as in the SVG. */
private fun DrawScope.inTile(lift: Float, block: DrawScope.() -> Unit) = withTransform({
    translate(256f, 232f + lift)
    scale(0.8f, 0.8f, Offset.Zero)
    translate(-256f, -265f)
}) { block() }

/**
 * How far the pin floats above rest, in 512 units, read while drawing so a step redraws the pin and nothing else;
 * zero when the phone asks for reduced motion.
 */
@Composable
private fun rememberFloat(): () -> Float {
    if (LocalReduceMotion.current) return { 0f }
    // the pin moves a few points in 1.8 s: 15 steps a second are a fraction of a point each
    val phase by rememberLoop(3600, stepsPerSecond = 15)
    return { mascotLift(phase) }
}

/** The pin's height at [phase] of its loop: up for half of it and back down the same way, eased at both ends. */
internal fun mascotLift(phase: Float): Float = -14f * EaseInOutSine.transform(1f - abs(1f - 2f * phase))

/**
 * The logo pin with a smiling lens, floating above its shadow. Used where the app talks about itself
 * (onboarding, empty lists, settings), not in the middle of a task.
 */
@Composable
fun PinMascot(size: Dp, modifier: Modifier = Modifier) {
    val float = rememberFloat()
    // tile crop: pin and shadow, no background (264 x 360 of 512)
    Canvas(modifier.size(size * (264f / 360f), size)) {
        val k = this.size.height / 360f
        val lift = float()
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
    val float = rememberFloat()
    Box(modifier.size(size).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val k = this.size.width / 512f
            withTransform({ scale(k, k, Offset.Zero) }) {
                drawLogoTile()
                inTile(float()) { drawPin() }
            }
        }
    }
}
