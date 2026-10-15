package ro.safetyplease.ui.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset

// How things appear, change and leave inside a screen, the same everywhere: what enters eases out of what is already
// there, what leaves goes faster than it came, and under reduced motion only opacity and color change.

/** [spec], or a jump to the end when the phone asks for reduced motion: for the springs and slides that move things. */
@Composable
fun <T> motionSpec(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else spec

/** A color easing to [target]: a selection, a tint, a button turning on. Color carries state, so it moves under reduced motion too. */
@Composable
fun animatedColor(target: Color, label: String = "color"): Color =
    animateColorAsState(target, tween(Motion.QUICK, easing = Motion.Standard), label = label).value

/** How [Reveal] opens and closes. */
fun revealIn(reduce: Boolean): EnterTransition =
    if (reduce) fadeIn(tween(Motion.QUICK))
    else fadeIn(tween(Motion.STANDARD, easing = Motion.Enter)) + expandVertically(tween(Motion.STANDARD, easing = Motion.Standard), expandFrom = Alignment.Top)

fun revealOut(reduce: Boolean): ExitTransition =
    if (reduce) fadeOut(tween(90))
    else fadeOut(tween(90)) + shrinkVertically(tween(Motion.QUICK, easing = Motion.Exit), shrinkTowards = Alignment.Top)

/**
 * Something that comes and goes in place, such as a banner, a hint or an error: it opens downwards as it fades in, and
 * closes faster than it opened, so what is under it slides instead of jumping.
 */
@Composable
fun Reveal(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(visible, modifier, enter = revealIn(reduce), exit = revealOut(reduce), label = "reveal", content = content)
}

/** [Reveal] for a value that may be missing: the last one stays on screen while it closes. */
@Composable
fun <T : Any> RevealValue(value: T?, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.(T) -> Unit) {
    val last = remember { LastValue<T>() }
    if (value != null) last.value = value
    Reveal(value != null, modifier) { last.value?.let { content(it) } }
}

private class LastValue<T : Any> {
    var value: T? = null
}

/**
 * Something new joining what is already on screen, such as a staff reply at the top of a list: it opens as [Reveal]
 * does when [fresh], and is simply there when the screen first shows it.
 */
@Composable
fun Arrive(fresh: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val reduce = LocalReduceMotion.current
    val shown = remember { MutableTransitionState(!fresh).apply { targetState = true } }
    AnimatedVisibility(shown, modifier, enter = revealIn(reduce), exit = revealOut(reduce), label = "arrive", content = content)
}

/** A small mark popping in and out, such as a badge, a check or a dot. Under reduced motion it only fades. */
@Composable
fun Pop(visible: Boolean, modifier: Modifier = Modifier, content: @Composable AnimatedVisibilityScope.() -> Unit) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible, modifier,
        enter = if (reduce) fadeIn(tween(Motion.QUICK)) else scaleIn(spring(dampingRatio = 0.6f, stiffness = 600f), initialScale = 0.6f) + fadeIn(tween(Motion.QUICK)),
        exit = if (reduce) fadeOut(tween(90)) else scaleOut(tween(Motion.QUICK, easing = Motion.Exit), targetScale = 0.6f) + fadeOut(tween(90)),
        label = "pop", content = content,
    )
}

/**
 * One content giving way to the next in place, such as a status word, a subtitle or a card: the new one fades in over
 * the old one, and the space they take eases to the new size.
 */
@Composable
fun <T> Swap(
    target: T,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    contentKey: (T) -> Any? = { it },
    content: @Composable AnimatedContentScope.(T) -> Unit,
) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        target, modifier,
        transitionSpec = {
            val size = if (reduce) null else SizeTransform(clip = false) { _, _ -> tween(Motion.STANDARD, easing = Motion.Standard) }
            (fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))).using(size)
        },
        contentAlignment = contentAlignment, label = "swap", contentKey = contentKey, content = content,
    )
}

/** Figures of one width, so a number that changes or counts down doesn't shake the text around it. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** A number rolling to its new value, upwards when it grows and downwards when it shrinks. */
@Composable
fun RollingCount(count: Int, style: TextStyle, color: Color, modifier: Modifier = Modifier, text: (Int) -> String = { it.toString() }) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        count, modifier,
        transitionSpec = {
            if (reduce) {
                fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))
            } else {
                val up = targetState > initialState
                (slideInVertically(tween(Motion.QUICK, easing = Motion.Enter)) { if (up) it / 2 else -it / 2 } + fadeIn(tween(Motion.QUICK))) togetherWith
                    (slideOutVertically(tween(Motion.QUICK, easing = Motion.Exit)) { if (up) -it / 2 else it / 2 } + fadeOut(tween(90)))
            }.using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center, label = "count",
    ) { n -> Text(text(n), style = style.tabular(), color = color, maxLines = 1) }
}

/** A list row that fades in and out, and slides to its new place when the list changes; under reduced motion it only fades. */
@Composable
fun LazyItemScope.listItem(): Modifier {
    val reduce = LocalReduceMotion.current
    return Modifier.animateItem(
        fadeInSpec = tween(Motion.STANDARD, easing = Motion.Enter),
        placementSpec = if (reduce) null else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
        fadeOutSpec = tween(Motion.QUICK),
    )
}

/**
 * A direction turning smoothly to [degrees], the short way round, however often it is updated: compass and route
 * arrows tick every quarter second or every second. It jumps under reduced motion.
 */
@Composable
fun rememberSmoothAngle(degrees: Float): State<Float> {
    val reduce = LocalReduceMotion.current
    val angle = remember { Animatable(degrees) }
    LaunchedEffect(degrees, reduce) {
        val target = angle.value + shortestTurn(angle.value, degrees)
        if (reduce) angle.snapTo(target) else angle.animateTo(target, spring(dampingRatio = 1f, stiffness = Spring.StiffnessLow))
    }
    return angle.asState()
}

/** An arrow pointing at [degrees], turning to each new direction smoothly instead of ticking. */
@Composable
fun Modifier.turnedTo(degrees: Float): Modifier {
    val angle = rememberSmoothAngle(degrees)
    return graphicsLayer { rotationZ = angle.value }
}

/** The turn from [from] to [to] degrees the short way, between -180 and 180. */
internal fun shortestTurn(from: Float, to: Float): Float = ((to - from) % 360f + 540f) % 360f - 180f
