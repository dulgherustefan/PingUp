package ro.safetyplease.ui.designsystem

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
import androidx.compose.ui.graphics.vector.PathParser

// The Ping Up logo and mascot, drawn from the logo's 512 SVG. This file is not under the GPL but under
// LicenseRef-PingUp-Brand (TRADEMARKS.md): a modified version of the app replaces it with drawings of its own.
// Mascot.kt places and animates whatever these two functions draw.

// the pin in its own coordinates, placed in the 512 tile like the app icon
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
internal fun DrawScope.drawPin() {
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

/** The app icon's tile, in 512 coordinates: the night, the rays under the pin and the pin's shadow. */
internal fun DrawScope.drawLogoTile() {
    val tile = Path().apply { addRoundRect(RoundRect(0f, 0f, 512f, 512f, CornerRadius(116f))) }
    clipPath(tile) {
        drawRect(
            Brush.radialGradient(0f to Color(0xFF444645), 0.55f to Color(0xFF151816), 1f to Color(0xFF101311), center = Offset(256f, 200f), radius = 340f),
            Offset.Zero, Size(512f, 512f),
        )
        val glow = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.4f)), 392f, 512f)
        Paths.rays.forEach { drawPath(it, glow) }
    }
    drawOval(Color.Black.copy(alpha = 0.25f), Offset(164f, 391f), Size(184f, 26f))
}
