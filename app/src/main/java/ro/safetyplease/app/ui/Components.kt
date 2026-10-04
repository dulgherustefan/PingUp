package ro.safetyplease.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Cat loc trebuie lasat jos: bara plutitoare in taburi, bara de gesturi in rest. */
val LocalBottomClearance = compositionLocalOf { 0.dp }

enum class ButtonKind { Primary, Secondary, Danger }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    val colors = LocalAppColors.current
    val source = remember { MutableInteractionSource() }
    val container = when {
        !enabled -> colors.cardHigh
        kind == ButtonKind.Primary -> colors.accent
        kind == ButtonKind.Danger -> colors.danger.copy(alpha = 0.14f)
        else -> colors.cardHigh
    }
    val content = when {
        !enabled -> colors.textSecondary
        kind == ButtonKind.Primary -> colors.onAccent
        kind == ButtonKind.Danger -> colors.danger
        else -> colors.text
    }
    Row(
        modifier
            .pressScale(source, enabled)
            .heightIn(min = if (compact) 44.dp else 52.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(source, LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 18.dp else 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Actiune secundara, doar text. Zona de atingere ramane de 48dp. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = LocalAppColors.current.accent) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp).pressScale(source).clip(CircleShape)
            .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = color) }
}

@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalAppColors.current.text,
    container: Color = Color.Transparent,
) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.size(48.dp).pressScale(source, pressed = 0.9f).padding(4.dp).clip(CircleShape).background(container)
            .clickable(source, LocalIndication.current, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(22.dp)) }
}

/** Pastila de filtru: cea aleasa ia culoarea de accent. */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: Int? = null) {
    val colors = LocalAppColors.current
    val container by animateColorAsState(if (selected) colors.accent else colors.card, tween(Motion.QUICK), label = "pill")
    val content by animateColorAsState(if (selected) colors.onAccent else colors.textSecondary, tween(Motion.QUICK), label = "pillText")
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.heightIn(min = 48.dp).pressScale(source)
            .selectable(selected = selected, interactionSource = source, indication = null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.height(38.dp).clip(CircleShape).background(container).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAppColors.current
    val border by animateColorAsState(if (selected) colors.accent else Color.Transparent, tween(Motion.QUICK), label = "cardBorder")
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .then(if (onClick != null) Modifier.pressScale(source, pressed = 0.985f) else Modifier)
            .clip(CardShape)
            .background(colors.card)
            .border(1.5.dp, border, CardShape)
            .then(if (onClick != null) Modifier.clickable(source, LocalIndication.current, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

fun initials(name: String): String {
    val parts = name.trim().split(' ').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

/** Cerc cu initiale. Prietenul aflat in apropiere are cercul verde. */
@Composable
fun Avatar(name: String, size: Dp = 48.dp, near: Boolean = false, group: Boolean = false, onDark: Boolean = false) {
    val colors = LocalAppColors.current
    val container = when {
        onDark -> Color.White.copy(alpha = 0.16f)
        near -> colors.forest
        else -> colors.avatar
    }
    val content = if (near || onDark) Color.White else colors.onAvatar
    // literele tin de marimea cercului, nu de marimea textului din setari
    val fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() }
    Box(Modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        if (group) Icon(AppIcons.People, null, tint = content, modifier = Modifier.size(size * 0.46f))
        else Text(initials(name), color = content, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconAction(AppIcons.Back, stringResource(R.string.back), onBack)
        val profileLabel = stringResource(R.string.open_profile)
        Row(
            Modifier.weight(1f)
                .then(
                    if (onTitleClick != null) Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = profileLabel, onClick = onTitleClick)
                    else Modifier
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        actions()
    }
}

@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { TopBar(title, onBack, subtitle, actions = actions) },
        bottomBar = bottomBar,
        containerColor = LocalAppColors.current.background,
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = LocalAppColors.current.text, modifier = modifier)
}

/** Camp de text in forma de pastila; creste in inaltime cand textul trece pe mai multe randuri. */
@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leading: ImageVector? = null,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    container: Color = LocalAppColors.current.card,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { contentDescription = placeholder },
        singleLine = maxLines == 1,
        maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { inner ->
            Row(
                Modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(container)
                    .padding(start = 18.dp, end = if (trailing != null) 4.dp else 18.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) {
                    Icon(leading, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
                if (trailing != null) trailing()
            }
        },
    )
}

/** Doua sau trei variante pe un rand; cea aleasa e plina. */
@Composable
fun <T> Segmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: (T) -> Color? = { null },
) {
    val colors = LocalAppColors.current
    Row(modifier.clip(CircleShape).background(colors.cardHigh).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((value, label) in options) {
            val isSelected = value == selected
            val fill = selectedColor(value)
            val container by animateColorAsState(
                if (isSelected) fill ?: colors.accent else Color.Transparent, tween(Motion.QUICK), label = "segment",
            )
            val content by animateColorAsState(
                when {
                    !isSelected -> colors.textSecondary
                    fill != null -> if (colors.dark) Color(0xFF1A1F1C) else Color.White
                    else -> colors.onAccent
                },
                tween(Motion.QUICK), label = "segmentText",
            )
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(CircleShape).background(container)
                    .selectable(selected = isSelected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = { onSelect(value) }),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.labelLarge, color = content) }
        }
    }
}

/** Card care apare doar cand ceva nu merge: spune ce lipseste si ofera butonul care rezolva. */
@Composable
fun ProblemCard(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = LocalAppColors.current.wait,
    action: String? = null,
    onAction: () -> Unit = {},
    dismiss: String? = null,
    onDismiss: () -> Unit = {},
) {
    val colors = LocalAppColors.current
    AppCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tone, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
        if (action != null || dismiss != null) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (dismiss != null) TextAction(dismiss, onDismiss, color = colors.textSecondary)
                if (action != null) {
                    Spacer(Modifier.width(4.dp))
                    AppButton(action, onAction, compact = true)
                }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, text: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    val colors = LocalAppColors.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HopLine(Modifier.width(132.dp).padding(bottom = 10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        action()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    confirmLabel: String = stringResource(R.string.confirm),
    destructive: Boolean = false,
    onConfirm: () -> Unit,
) {
    val colors = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        titleContentColor = colors.text,
        textContentColor = colors.textSecondary,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextAction(
                confirmLabel,
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                color = if (destructive) colors.danger else colors.accent,
            )
        },
        dismissButton = { TextAction(stringResource(R.string.cancel), onDismiss, color = colors.textSecondary) },
    )
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = colors.text, textAlign = TextAlign.End)
    }
}

/**
 * Semnul aplicatiei: puncte legate intre ele, cu un impuls care trece din punct in punct, cum trece
 * un mesaj din telefon in telefon.
 */
@Composable
fun HopLine(
    modifier: Modifier = Modifier,
    dots: Int = 5,
    dotSize: Dp = 9.dp,
    base: Color = LocalAppColors.current.cardHigh,
    highlight: Color = LocalAppColors.current.accent,
) {
    val last = dots - 1
    val progress = if (LocalReduceMotion.current) last.toFloat() else {
        val transition = rememberInfiniteTransition(label = "hop")
        transition.animateFloat(
            initialValue = -0.5f, targetValue = last + 1.4f,
            animationSpec = infiniteRepeatable(tween(dots * 560, easing = LinearEasing), RepeatMode.Restart),
            label = "hopProgress",
        ).value
    }
    Canvas(modifier.height(dotSize * 2)) {
        val radius = dotSize.toPx() / 2
        val gap = (size.width - radius * 4) / last
        val y = size.height / 2
        val stroke = 2.dp.toPx()
        // la capatul ciclului impulsul se stinge, ca reluarea sa nu fie o taietura
        val fade = (1f - (progress - last) / 1.2f).coerceIn(0f, 1f)
        val lit = highlight.copy(alpha = highlight.alpha * fade)
        fun x(index: Int) = radius * 2 + gap * index
        for (i in 0 until last) {
            drawLine(base, Offset(x(i), y), Offset(x(i + 1), y), stroke, StrokeCap.Round)
            val fill = (progress - i).coerceIn(0f, 1f)
            if (fill > 0f) drawLine(lit, Offset(x(i), y), Offset(x(i) + gap * fill, y), stroke, StrokeCap.Round)
        }
        for (i in 0..last) {
            val near = (1f - abs(progress - i)).coerceIn(0f, 1f)
            drawCircle(base, radius, Offset(x(i), y))
            if (progress >= i) drawCircle(lit, radius * (1f + 0.45f * near), Offset(x(i), y))
        }
    }
}

/** Bifa care se deseneaza singura: momentul de confirmare dupa un raport trimis sau un prieten adaugat. */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, size: Dp = 72.dp, color: Color = LocalAppColors.current.ok) {
    val progress = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(Unit) {
        if (reduce) progress.snapTo(1f) else progress.animateTo(1f, tween(560, easing = Motion.Enter))
    }
    Canvas(modifier.size(size)) {
        val p = progress.value
        val pop = (p / 0.55f).coerceIn(0f, 1f)
        val scale = 0.7f + 0.3f * pop + 0.05f * sin(pop * PI).toFloat()
        val center = Offset(this.size.width / 2, this.size.height / 2)
        drawCircle(color.copy(alpha = 0.16f * pop), this.size.minDimension / 2 * scale, center)
        val draw = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
        if (draw > 0f) {
            val w = this.size.width
            val h = this.size.height
            val full = Path().apply {
                moveTo(w * 0.30f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.66f)
                lineTo(w * 0.71f, h * 0.37f)
            }
            val measure = PathMeasure().apply { setPath(full, false) }
            val part = Path()
            measure.getSegment(0f, measure.length * draw, part, true)
            drawPath(part, color, style = Stroke(w * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Iconita si cuvant pentru o stare: culoarea singura nu spune niciodata nimic. */
@Composable
fun StateLabel(icon: ImageVector, text: String, tone: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tone, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = tone, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FullScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(LocalAppColors.current.background)) { content() }
}
