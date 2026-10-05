package ro.safetyplease.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ro.safetyplease.app.R
import ro.safetyplease.app.data.MsgStatus

/** Cat loc trebuie lasat jos: bara de taburi plutitoare in ecranele principale, bara de gesturi in rest. */
val LocalBottomClearance = compositionLocalOf { 0.dp }

/** Marginea laterala, ca pe iPhone. */
val Gutter = 16.dp

/** Inaltimea barei de sus, fara bara de stare. */
val NavHeight = 52.dp

/** Primary e albastrul plin, Secondary e gri, Danger e rosul pentru stergere. */
enum class ButtonKind { Primary, Secondary, Danger }

/** Butonul iOS 26: o capsula plina, text de 17 ingrosat. [compact] e varianta mica, pentru randuri si bannere. */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val c = AppTheme.colors
    val fill = when {
        !enabled -> c.fill
        kind == ButtonKind.Primary -> c.accent
        kind == ButtonKind.Danger -> c.red
        else -> c.fill
    }
    val ink = when {
        !enabled -> c.tertiaryLabel
        kind == ButtonKind.Secondary -> c.label
        kind == ButtonKind.Primary -> c.onAccent
        else -> Color.White
    }
    Box(
        modifier.heightIn(min = if (compact) 36.dp else 50.dp).clip(CircleShape).background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) 16.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = if (compact) MaterialTheme.typography.subheadline.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.headline,
            color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Butonul simplu iOS: doar textul albastru, cu loc de atins de 44. */
@Composable
fun TextLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = AppTheme.colors.accent) {
    Box(
        modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.body, color = color)
    }
}

/** O iconita care se poate atinge, fara fond. */
@Composable
fun IconBtn(icon: ImageVector, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = AppTheme.colors.label) {
    Box(
        modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = tint) }
}

/** Butonul rotund de sticla din bara de sus: inapoi, inchide. */
@Composable
fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, backdrop: Backdrop?, modifier: Modifier = Modifier) {
    Box(
        modifier.size(44.dp).glass(backdrop, CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp), tint = AppTheme.colors.label) }
}

/** O capsula de sticla cu mai multe iconite, ca grupul camera + scrie din Signal. */
@Composable
fun GlassCapsule(backdrop: Backdrop?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.height(44.dp).glass(backdrop, CircleShape).padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/** O iconita dintr-o capsula de sticla. */
@Composable
fun CapsuleIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 46.dp, height = 44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(22.dp), tint = AppTheme.colors.label) }
}

/** Textul din bara de sus creste cu setarile telefonului doar pana la 115%, ca pe iPhone: altfel nu mai incape in bara. */
@Composable
fun NavText(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.15f)), content = content)
}

/** Sub bara de sus, cand continutul a urcat sub ea: fundalul, plin pana la marginea barei, apoi se stinge. */
@Composable
fun TopEdge(top: Dp, color: Color, visible: Boolean) {
    val edge by animateFloatAsState(if (visible) 1f else 0f, tween(Motion.QUICK), label = "edge")
    val solid = top + NavHeight
    val height = solid + 22.dp
    val glow = AppTheme.colors.glow
    // aceeasi lumina ca fundalul de dedesubt, plina pana la marginea barei, apoi stinsa printr-o masca
    Box(
        Modifier.fillMaxWidth().height(height)
            .graphicsLayer { alpha = edge; compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                drawRect(screenGlow(size.width, color, glow))
                drawRect(Brush.verticalGradient(0f to Color.Black, solid / height to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            },
    )
}

/**
 * Ecranul iOS 26: continutul trece pe sub bara de sus, care are titlul centrat si butoane de sticla in colturi.
 * Cand lista a coborat, sub bara apare o estompare spre culoarea fundalului, ca sa ramana titlul lizibil.
 * [content] primeste spatiul de lasat sus si jos.
 */
@Composable
fun NavScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    background: Color = AppTheme.colors.background,
    scrolled: Boolean = false,
    subtitleLeading: (@Composable () -> Unit)? = null,
    leading: @Composable (Backdrop) -> Unit = {},
    trailing: @Composable (Backdrop) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = rememberBackdrop()
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val padding = PaddingValues(top = top + NavHeight + 4.dp, bottom = LocalBottomClearance.current + 16.dp)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().backdropSource(backdrop).screenBackground(background)) { content(padding) }
        TopEdge(top, background, scrolled)
        // ca UINavigationBar: titlul sta pe centru cat timp incape intre butoane, altfel se muta spre partea libera;
        // titlul si randul de sub el se centreaza separat, ca o stare mai lunga sa nu mute titlul
        Layout(
            content = {
                Box { leading(backdrop) }
                NavText { Text(title, style = MaterialTheme.typography.headline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Box { trailing(backdrop) }
                if (subtitle != null) {
                    NavText {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            subtitleLeading?.invoke()
                            Text(
                                subtitle, style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            modifier = Modifier.statusBarsPadding().fillMaxWidth().height(NavHeight).padding(horizontal = Gutter),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val lead = measurables[0].measure(loose)
            val trail = measurables[2].measure(loose)
            val gap = 8.dp.roundToPx()
            val width = constraints.maxWidth
            val room = (width - lead.width - trail.width - 2 * gap).coerceAtLeast(0)
            val head = measurables[1].measure(loose.copy(maxWidth = room))
            val sub = measurables.getOrNull(3)?.measure(loose.copy(maxWidth = room))
            fun xFor(w: Int) = ((width - w) / 2).coerceIn(lead.width + gap, (width - trail.width - gap - w).coerceAtLeast(lead.width + gap))
            layout(width, constraints.maxHeight) {
                val height = constraints.maxHeight
                val top = (height - head.height - (sub?.height ?: 0)) / 2
                lead.place(0, (height - lead.height) / 2)
                head.place(xFor(head.width), top)
                sub?.place(xFor(sub.width), top + head.height)
                trail.place(width - trail.width, (height - trail.height) / 2)
            }
        }
    }
}

/** Ecranul deschis peste taburi, cu sageata inapoi de sticla. Pastrat pentru ecranul demo. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        NavScreen(
            title, subtitle = subtitle, background = AppTheme.colors.grouped, scrolled = true,
            leading = { b -> if (onBack != null) GlassIconButton(Sym.Back, stringResource(R.string.back), onBack, b) },
            trailing = { Row(content = actions) },
            content = content,
        )
        Box(Modifier.align(Alignment.BottomCenter)) { bottomBar() }
    }
}

fun initials(name: String): String {
    val parts = name.trim().split(' ').filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

/**
 * Bula unui om, ca in Signal: initialele pe o culoare pastel aleasa dupa nume, deci acelasi prieten arata la fel peste tot.
 * Un grup are iconita de grup; cine e in apropiere are un punct verde pe margine.
 */
@Composable
fun Avatar(name: String, size: Dp = 56.dp, near: Boolean = false, group: Boolean = false) {
    val colors = AppTheme.colors
    val (fill, ink) = colors.avatars[(name.hashCode() and 0x7fffffff) % colors.avatars.size]
    // literele tin de marimea bulei, nu de marimea textului din setari
    val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
    Box {
        Box(Modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            if (group) Icon(Sym.Group, null, tint = ink, modifier = Modifier.size(size * 0.5f))
            else Text(
                initials(name), color = ink, fontSize = fontSize, lineHeight = fontSize, fontFamily = TextFont,
                fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
        if (near) {
            val dot = (size * 0.26f).coerceAtLeast(12.dp)
            Box(
                Modifier.align(Alignment.BottomEnd).size(dot).clip(CircleShape).background(colors.background).padding(2.dp)
                    .clip(CircleShape).background(colors.green),
            )
        }
    }
}

/** Numarul de necitite: cerc albastru cu cifra alba, cat creste numarul se lungeste. */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier, color: Color = AppTheme.colors.accent) {
    Box(
        modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).clip(CircleShape).background(color).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val style = MaterialTheme.typography.footnote
        Text(if (count > 99) "99+" else count.toString(), style = style.copy(lineHeight = style.fontSize), color = if (color == AppTheme.colors.accent) AppTheme.colors.onAccent else Color.White, maxLines = 1)
    }
}

/** Campul de cautare: o capsula cu umplere translucida, mereu o treapta peste fundal, cu lupa in fata. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fill: Color = AppTheme.colors.fill,
    trailing: @Composable () -> Unit = {},
) {
    val c = AppTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { contentDescription = placeholder },
        singleLine = true,
        textStyle = MaterialTheme.typography.body.copy(color = c.label),
        cursorBrush = SolidColor(c.accent),
        decorationBox = { inner ->
            Row(
                Modifier.height(42.dp).clip(CircleShape).background(fill).padding(start = 13.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Sym.Search, null, Modifier.size(18.dp), tint = c.secondaryLabel)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.body, color = c.secondaryLabel, maxLines = 1)
                    inner()
                }
                trailing()
            }
        },
    )
}

/** Campul unui formular: un dreptunghi gri, rotunjit, cu textul de ajutor dedesubt. */
@Composable
fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    supporting: String? = null,
    background: Color = AppTheme.colors.fill,
) {
    val c = AppTheme.colors
    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
            singleLine = maxLines == 1,
            maxLines = maxLines,
            textStyle = MaterialTheme.typography.body.copy(color = c.label),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(if (maxLines == 1) 25.dp else 20.dp))
                        .background(background).padding(horizontal = 16.dp, vertical = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) Text(label, style = MaterialTheme.typography.body, color = c.secondaryLabel, maxLines = 1)
                    inner()
                }
            },
        )
        if (supporting != null) {
            Text(
                supporting, style = MaterialTheme.typography.footnote, color = c.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** Titlul unui grup de randuri, ca in setarile iOS 26: ingrosat, gri, aliniat cu textul randurilor. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.secondaryLabel, top: Dp = 24.dp) {
    Text(
        text, style = MaterialTheme.typography.headline, color = color,
        modifier = modifier.fillMaxWidth().padding(start = Gutter * 2, end = Gutter * 2, top = top, bottom = 8.dp),
    )
}

/** Grupul de randuri al iOS-ului: un card cu colturi mari, pe fundalul gri al ecranului. */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Gutter).clip(RoundedCornerShape(26.dp)).background(AppTheme.colors.cell),
        content = content,
    )
}

/** Linia subtire dintre doua randuri, care incepe de unde incepe textul. */
@Composable
fun GroupDivider(start: Dp = 56.dp) {
    val hairline = with(LocalDensity.current) { 1.toDp() }
    Box(Modifier.fillMaxWidth().padding(start = start).height(hairline).background(AppTheme.colors.separator))
}

/**
 * Un rand dintr-un grup: iconita, titlul cu o lamurire dedesubt, apoi valoarea si sageata in dreapta.
 * Fara [onClick] randul nu se poate atinge.
 */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    tint: Color = AppTheme.colors.label,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = AppTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Gutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(24.dp), tint = tint)
            Spacer(Modifier.width(16.dp))
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.body, color = tint)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.footnote, color = c.secondaryLabel)
        }
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.body, color = c.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Icon(Sym.Chevron, null, Modifier.size(14.dp), tint = c.tertiaryLabel)
        }
    }
}

/** Comutatorul iOS: sina verde cand e pornit, gri cand e oprit, cu bila alba care aluneca. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val track by animateColorAsState(if (checked) c.green else c.switchOff, tween(Motion.STANDARD), label = "track")
    val x by animateDpAsState(if (checked) 22.dp else 2.dp, spring(dampingRatio = 0.75f, stiffness = 500f), label = "thumb")
    Box(
        modifier.size(width = 51.dp, height = 31.dp).clip(CircleShape).background(track)
            .then(if (onCheckedChange != null) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange) else Modifier),
    ) {
        Box(
            Modifier.offset { IntOffset(x.roundToPx(), 2.dp.roundToPx()) }.size(27.dp).shadow(3.dp, CircleShape, ambientColor = Color(0x1F000000), spotColor = Color(0x29000000))
                .clip(CircleShape).background(Color.White),
        )
    }
}

/** Randul cu comutator: tot randul se poate atinge. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, icon: ImageVector? = null) {
    GroupRow(
        title, modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange), subtitle = subtitle, icon = icon,
        trailing = { IosSwitch(checked, null) },
    )
}

/** Comutatorul cu segmente al iOS-ului: o sina gri si pastila alba sub varianta aleasa. */
@Composable
fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = AppTheme.colors
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(modifier.fillMaxWidth().height(36.dp).clip(CircleShape).background(c.fill).padding(2.dp).selectableGroup()) {
        val w = maxWidth / options.size
        val x by animateDpAsState(w * index, spring(dampingRatio = 0.85f, stiffness = 500f), label = "segment")
        Box(
            Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(w).fillMaxHeight().shadow(2.dp, CircleShape, ambientColor = Color(0x14000000), spotColor = Color(0x1F000000))
                .clip(CircleShape).background(c.segmentPill),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEach { (value, label) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape)
                        .selectable(selected = value == selected, role = Role.Tab, onClick = { onSelect(value) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, style = MaterialTheme.typography.footnote, fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Medium,
                        color = c.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * O actiune cu iconita, ca butoanele de sub antetul unui contact in Signal: un dreptunghi rotunjit
 * cu iconita si eticheta in el. Aleasa, se umple cu verde.
 */
@Composable
fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, background: Color = AppTheme.colors.fill) {
    val c = AppTheme.colors
    val fill by animateColorAsState(if (selected) c.accent else background, tween(Motion.QUICK), label = "tile")
    val ink = if (selected) c.onAccent else c.label
    Column(
        modifier.heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(fill)
            .selectable(selected = selected, role = Role.Button, onClick = onClick).padding(start = 6.dp, end = 6.dp, top = 14.dp, bottom = 10.dp),
        // continutul porneste de sus: intr-un rand intins dupa o eticheta pe doua randuri, iconitele raman pe aceeasi linie
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Top,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = ink)
        TileLabel(label, ink, if (selected) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/** Eticheta unei placi: se micsoreaza cat sa incapa pe un rand cel mai lung cuvant, ca sa nu se rupa la jumatate. */
@Composable
private fun TileLabel(text: String, color: Color, weight: FontWeight) {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.caption1.copy(fontWeight = weight, textAlign = TextAlign.Center, color = color)
    var laidOut by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Layout simplu, nu BoxWithConstraints: placile stau si in randuri care le cer inaltimea intrinseca
    Layout(
        modifier = Modifier.padding(top = 6.dp).semantics { contentDescription = text }.drawBehind { laidOut?.let { drawText(it) } },
    ) { _, constraints ->
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        fun widest(style: TextStyle) = text.split(' ').maxOf { measurer.measure(it, style, maxLines = 1).size.width }
        var style = base
        while (widest(style) > room && style.fontSize.value > 8f) {
            style = style.copy(fontSize = style.fontSize * 0.92f, lineHeight = style.lineHeight * 0.92f)
        }
        val result = measurer.measure(text, style, TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = room))
        laidOut = result
        layout(result.size.width, result.size.height) {}
    }
}

/** Mesajul de deasupra listei cand ceva nu merge: ce lipseste si butonul care rezolva. */
@Composable
fun Banner(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
    dismiss: String? = null,
    onDismiss: () -> Unit = {},
) {
    val c = AppTheme.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.fill).padding(16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(if (warning) Sym.Report else Sym.Info, null, Modifier.size(22.dp), tint = if (warning) c.orange else c.accent)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headline)
                Text(text, style = MaterialTheme.typography.subheadline, color = c.secondaryLabel, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (action != null || dismiss != null) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (dismiss != null) AppButton(dismiss, onDismiss, kind = ButtonKind.Secondary, compact = true)
                if (action != null) AppButton(action, onAction, compact = true)
            }
        }
    }
}

/** Ecranul gol, ca pe iPhone: iconita gri, titlul, o fraza si, de obicei, butonul care il umple. */
@Composable
fun EmptyState(
    title: String,
    text: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    illustration: (@Composable () -> Unit)? = null,
    action: @Composable () -> Unit = {},
) {
    val c = AppTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (illustration != null) {
            illustration()
            Spacer(Modifier.height(20.dp))
        } else if (icon != null) {
            Icon(icon, null, Modifier.size(48.dp), tint = c.secondaryLabel)
            Spacer(Modifier.height(16.dp))
        }
        Text(title, style = MaterialTheme.typography.title2, textAlign = TextAlign.Center)
        if (text != null) {
            Text(
                text, style = MaterialTheme.typography.subheadline, color = c.secondaryLabel, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        action()
    }
}

/** Alerta iOS 26: un card rotunjit in mijloc, titlul si mesajul centrate, butoanele ca doua capsule. */
@Composable
fun AppDialog(
    onDismiss: () -> Unit,
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String? = stringResource(R.string.cancel),
    confirmEnabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val c = AppTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(34.dp)).background(c.dialog)
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 18.dp), content = content)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (dismissLabel != null) AppButton(dismissLabel, onDismiss, Modifier.weight(1f), kind = ButtonKind.Secondary)
                AppButton(
                    confirmLabel, onConfirm, Modifier.weight(1f), enabled = confirmEnabled,
                    kind = if (destructive) ButtonKind.Danger else ButtonKind.Primary,
                )
            }
        }
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
    AppDialog(
        onDismiss, title, confirmLabel,
        onConfirm = {
            onConfirm()
            onDismiss()
        },
        destructive = destructive,
    ) {
        Text(
            text, style = MaterialTheme.typography.footnote, color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Meniul contextual iOS: un card rotunjit cu randuri; iconita sta in dreapta textului. */
@Composable
fun AppMenu(expanded: Boolean, onDismiss: () -> Unit, offset: DpOffset = DpOffset(0.dp, 4.dp), content: @Composable ColumnScope.() -> Unit) {
    val c = AppTheme.colors
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss, offset = offset, modifier = Modifier.widthIn(min = 240.dp),
        shape = RoundedCornerShape(22.dp), containerColor = c.menu,
        // o umbra mai mare iese din fereastra meniului si se vede taiata drept
        tonalElevation = 0.dp, shadowElevation = 6.dp, border = BorderStroke(0.5.dp, c.glassRim), content = content,
    )
}

@Composable
fun MenuRow(label: String, onClick: () -> Unit, icon: ImageVector? = null, danger: Boolean = false) {
    val c = AppTheme.colors
    val color = if (danger) c.red else c.label
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.body, color = color, modifier = Modifier.weight(1f))
        if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = color)
    }
}

/** Un rand mic de stare: iconita si cuvintele ei. */
@Composable
fun StatusLabel(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = AppTheme.colors.secondaryLabel) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.subheadline, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Randul de lamurire de sub un titlu. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = AppTheme.colors.secondaryLabel) {
    Text(text, style = MaterialTheme.typography.subheadline, color = color, modifier = modifier)
}

/** Iconita intr-un cerc colorat, in fata unui rand: categoria unui incident, o actiune. */
@Composable
fun IconCircle(icon: ImageVector, container: Color, content: Color, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(container), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun FullScreen(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(AppTheme.colors.background)) { content() }
}

/**
 * Starea unui mesaj trimis, desenata ca in Signal: ceasul cat asteapta un telefon, un cerc cu bifa cand a plecat,
 * doua cercuri cand a ajuns, semnul exclamarii cand n-a mers. [behind] e culoarea de sub iconita,
 * cu care al doilea cerc il acopera pe primul.
 */
@Composable
fun DeliveryIcon(status: MsgStatus, tint: Color, behind: Color, modifier: Modifier = Modifier, iconSize: Dp = 13.dp) {
    val wide = status == MsgStatus.DELIVERED
    Canvas(modifier.size(width = if (wide) iconSize * 1.45f else iconSize, height = iconSize)) {
        val s = iconSize.toPx()
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val r = s * 0.42f
        val center = Offset(s / 2, s / 2)
        when (status) {
            MsgStatus.QUEUED -> {
                drawCircle(tint, r, center, style = stroke)
                drawLine(tint, center, Offset(center.x, center.y - r * 0.6f), stroke.width, StrokeCap.Round)
                drawLine(tint, center, Offset(center.x + r * 0.45f, center.y + r * 0.2f), stroke.width, StrokeCap.Round)
            }
            MsgStatus.SENT -> checkCircle(tint, center, r, stroke)
            MsgStatus.DELIVERED -> {
                checkCircle(tint, center, r, stroke)
                val second = Offset(center.x + s * 0.45f, center.y)
                drawCircle(behind, r + stroke.width, second)
                checkCircle(tint, second, r, stroke)
            }
            else -> {
                drawCircle(tint, r, center, style = stroke)
                drawLine(tint, Offset(center.x, center.y - r * 0.5f), Offset(center.x, center.y + r * 0.1f), stroke.width, StrokeCap.Round)
                drawCircle(tint, stroke.width * 0.7f, Offset(center.x, center.y + r * 0.45f))
            }
        }
    }
}

private fun DrawScope.checkCircle(tint: Color, center: Offset, r: Float, stroke: Stroke) {
    drawCircle(tint, r, center, style = stroke)
    val check = Path().apply {
        moveTo(center.x - r * 0.45f, center.y + r * 0.02f)
        lineTo(center.x - r * 0.12f, center.y + r * 0.34f)
        lineTo(center.x + r * 0.48f, center.y - r * 0.3f)
    }
    drawPath(check, tint, style = stroke)
}
