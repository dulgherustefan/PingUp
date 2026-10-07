package ro.safetyplease.app.ui.designsystem

import android.os.Build
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Blurring what's behind a bar needs Android 12+; below that glass is nearly opaque. */
val CanBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * What shows through glass: a screen's content, recorded into a graphics layer on every draw.
 * Glass must sit next to the content, not inside it, or it would draw into its own recording.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marks the content seen through glass. Its background must be drawn inside, or the blur has gaps. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Glass: blurred content behind, a tint, a thin outline and a soft shadow.
 * Without [backdrop] (or below Android 12) only the tint remains, nearly opaque.
 */
@Composable
fun Modifier.glass(backdrop: Backdrop?, shape: Shape, blur: Dp = 24.dp, shadow: Boolean = true): Modifier {
    val colors = AppTheme.colors
    val layer = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val live = backdrop != null && CanBlur
    return this
        .then(if (shadow) Modifier.shadow(10.dp, shape, ambientColor = Color(0x14000000), spotColor = Color(0x29000000)) else Modifier)
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawBehind {
            if (live && backdrop != null) {
                val radius = blur.toPx()
                layer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                layer.record {
                    translate(backdrop.origin.x - position.x, backdrop.origin.y - position.y) { drawLayer(backdrop.layer) }
                }
                val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
                clipPath(outline) { drawLayer(layer) }
            }
        }
        .background(if (live) colors.glass else colors.glassSolid, shape)
        .border(0.75.dp, colors.glassRim, shape)
        .clip(shape)
}

/** Press feedback without a ripple: a gray layer over the pressed item while the finger is down. */
class PressHighlight(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource, color)

    override fun equals(other: Any?) = other is PressHighlight && other.color == color

    override fun hashCode() = color.hashCode()

    private class Node(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                var presses = 0
                source.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> presses++
                        is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    }
                    if ((presses > 0) != pressed) {
                        pressed = presses > 0
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            if (pressed) drawRect(color)
            drawContent()
        }
    }
}
