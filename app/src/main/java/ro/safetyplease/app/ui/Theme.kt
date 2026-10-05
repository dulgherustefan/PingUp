package ro.safetyplease.app.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import ro.safetyplease.app.R
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.Severity

/**
 * Culorile aplicatiei, dupa Signal pe iPhone (iOS 26): fundal alb sau negru, text in trei trepte de gri,
 * albastrul Signal ca accent si sticla pentru barele care plutesc peste continut.
 */
@Immutable
class AppColors(
    val dark: Boolean,
    /** Fundalul listelor si al conversatiilor. */
    val background: Color,
    /** Fundalul ecranelor cu grupuri de randuri (setari) si celulele de pe el. */
    val grouped: Color,
    val cell: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    /** Umplerea campurilor si a butoanelor gri. */
    val fill: Color,
    val searchFill: Color,
    /** Randul apasat. */
    val pressed: Color,
    val accent: Color,
    val red: Color,
    val green: Color,
    val orange: Color,
    val bubbleIn: Color,
    /** Baloanele trimise au un gradient pe toata inaltimea ecranului: mai inchis sus, mai deschis jos. */
    val bubbleOutTop: Color,
    val bubbleOutBottom: Color,
    val onBubbleOut: Color,
    val onBubbleOutSecondary: Color,
    /** Sticla: nuanta peste fundalul estompat, nuanta fara estompare (Android sub 12), conturul si pastila tabului ales. */
    val glass: Color,
    val glassSolid: Color,
    val glassRim: Color,
    val glassPill: Color,
    /** Fondul dialogurilor si al meniurilor contextuale. */
    val dialog: Color,
    val menu: Color,
    /** Sina comutatorului oprit si pastila variantei alese din comutatorul cu segmente. */
    val switchOff: Color,
    val segmentPill: Color,
    /** Cercul de trimis din conversatie. */
    val send: Color,
    /** Culorile numelor in grupuri, cate una pe om. */
    val names: List<Color>,
) {
    /** Fondul si cerneala unui avatar; aceleasi in tema deschisa si in cea inchisa. */
    val avatars: List<Pair<Color, Color>> = AvatarPairs

    /** Culorile zonelor de pe harta, in ordinea din venue.json. Noaptea, ca pe Apple Maps: zona intunecata, numele deschis. */
    val zones: List<Color> =
        if (dark) AvatarPairs.map { it.second.copy(alpha = 0.34f).compositeOver(Color(0xFF1C1C1E)) } else AvatarPairs.map { it.first }
    val zoneInk: List<Color> = if (dark) AvatarPairs.map { it.first } else AvatarPairs.map { it.second }

    // nume pastrate pentru ecranul demo
    val ok: Color get() = green
    val wait: Color get() = orange
    val danger: Color get() = red
    val textSecondary: Color get() = secondaryLabel

    fun severity(severity: Int): Color = if (severity == Severity.URGENT) red else orange

    fun status(status: Int): Color = when (status) {
        AckStatus.RESOLVED, AckStatus.ACKNOWLEDGED -> green
        else -> orange
    }
}

private val AvatarPairs = listOf(
    Color(0xFFE3E3FE) to Color(0xFF3838F5),
    Color(0xFFDDE7FC) to Color(0xFF1251D3),
    Color(0xFFD8E8F0) to Color(0xFF086DA0),
    Color(0xFFCDE4CD) to Color(0xFF067906),
    Color(0xFFEAE0FD) to Color(0xFF661AFF),
    Color(0xFFF5E3FE) to Color(0xFF9F00F0),
    Color(0xFFF6D8EC) to Color(0xFFB8057C),
    Color(0xFFF5D7D7) to Color(0xFFBE0404),
    Color(0xFFFEF5D0) to Color(0xFF836B01),
    Color(0xFFEAE6D5) to Color(0xFF7D6F40),
    Color(0xFFD2D2DC) to Color(0xFF4F4F6D),
    Color(0xFFD7D7D9) to Color(0xFF5C5C5C),
)

private val LightColors = AppColors(
    dark = false,
    background = Color.White,
    grouped = Color(0xFFEFEFF0),
    cell = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0xB83C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0xFFC6C6C8),
    fill = Color(0x1F767680),
    searchFill = Color(0xFFF3F3F3),
    pressed = Color(0xFFD4D4D6),
    accent = Color(0xFF2267F5),
    red = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
    bubbleIn = Color(0xFFE9E9E9),
    bubbleOutTop = Color(0xFF0552F0),
    bubbleOutBottom = Color(0xFF2C6BED),
    onBubbleOut = Color.White,
    onBubbleOutSecondary = Color(0xCCFFFFFF),
    glass = Color(0xB3FFFFFF),
    glassSolid = Color(0xF5FCFCFC),
    glassRim = Color(0x14000000),
    glassPill = Color(0x14000000),
    dialog = Color.White,
    menu = Color(0xFFF9F9F9),
    switchOff = Color(0xFFE9E9EA),
    segmentPill = Color.White,
    send = Color(0xFF1D6DF1),
    names = listOf(
        0xFF006DA3, 0xFF067906, 0xFFB814B8, 0xFFC13215, 0xFF5B6976, 0xFFCC0066, 0xFF2E51FF, 0xFF007575,
        0xFF9C5711, 0xFFD00B4D, 0xFF8F2AF4, 0xFF3D7406, 0xFFD00B0B, 0xFF007A3D, 0xFF5151F6, 0xFF866118,
    ).map { Color(it) },
)

private val DarkColors = AppColors(
    dark = true,
    background = Color.Black,
    grouped = Color.Black,
    cell = Color(0xFF1C1C1E),
    label = Color.White,
    secondaryLabel = Color(0xB3EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0xFF38383A),
    fill = Color(0x3D767680),
    searchFill = Color(0xFF1C1C1E),
    pressed = Color(0xFF3A3A3D),
    accent = Color(0xFF2D70FA),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    bubbleIn = Color(0xFF2C2C2E),
    bubbleOutTop = Color(0xFF0552F0),
    bubbleOutBottom = Color(0xFF2C6BED),
    onBubbleOut = Color(0xFFE9E9E9),
    onBubbleOutSecondary = Color(0x99FFFFFF),
    glass = Color(0xA6202020),
    glassSolid = Color(0xF5181818),
    glassRim = Color(0x24FFFFFF),
    glassPill = Color(0x26FFFFFF),
    dialog = Color(0xFF2C2C2E),
    menu = Color(0xFF2C2C2E),
    switchOff = Color(0xFF39393D),
    segmentPill = Color(0xFF636366),
    send = Color(0xFF1655ED),
    names = listOf(
        0xFF00A7FA, 0xFF0AB80A, 0xFFF65AF6, 0xFFFF6F52, 0xFF8BA1B6, 0xFFF76EB2, 0xFF8599FF, 0xFF00B2B2,
        0xFFD5920B, 0xFFFF6B9C, 0xFFBF80FF, 0xFF5EB309, 0xFFFF7070, 0xFF00B85C, 0xFF9494FF, 0xFFD68F00,
    ).map { Color(it) },
)

val LocalAppColors = staticCompositionLocalOf { LightColors }

private fun inter(opticalSize: TextUnit) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            R.font.inter_variable, weight,
            variationSettings = FontVariation.Settings(weight, FontStyle.Normal, FontVariation.opticalSizing(opticalSize)),
        )
    },
)

/** Inter, cel mai apropiat de fontul iPhone-ului: varianta de text pana la 22, cea de titluri peste. */
val TextFont = inter(14.sp)
val DisplayFont = inter(32.sp)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    letterSpacing = tracking.em, lineHeightStyle = Trim,
)

/**
 * Scara de text iOS (marimea implicita „Large”), pusa in sloturile Material ca sa o foloseasca si componentele lor.
 * Numele iOS sunt mai jos, ca extensii: `typography.headline`, `typography.footnote`...
 */
private val IosTypography = Typography(
    headlineLarge = style(DisplayFont, 34, 41, FontWeight.Bold, 0.0),
    headlineMedium = style(DisplayFont, 28, 34, FontWeight.Bold, 0.0),
    headlineSmall = style(TextFont, 22, 28, FontWeight.Bold, -0.0183),
    titleLarge = style(TextFont, 20, 25, FontWeight.SemiBold, -0.0167),
    titleMedium = style(TextFont, 17, 22, FontWeight.SemiBold, -0.0128),
    titleSmall = style(TextFont, 15, 20, FontWeight.SemiBold, -0.0088),
    bodyLarge = style(TextFont, 17, 22, FontWeight.Normal, -0.0128),
    bodyMedium = style(TextFont, 15, 20, FontWeight.Normal, -0.0088),
    bodySmall = style(TextFont, 13, 18, FontWeight.Normal, -0.0032),
    labelLarge = style(TextFont, 17, 22, FontWeight.SemiBold, -0.0128),
    labelMedium = style(TextFont, 12, 16, FontWeight.Normal, 0.0005),
    labelSmall = style(TextFont, 11, 13, FontWeight.Normal, 0.0048),
    displaySmall = style(DisplayFont, 34, 41, FontWeight.Bold, 0.0),
)

val Typography.largeTitle: TextStyle get() = headlineLarge
val Typography.title1: TextStyle get() = headlineMedium
val Typography.title2: TextStyle get() = headlineSmall
val Typography.title3: TextStyle get() = titleLarge
val Typography.headline: TextStyle get() = titleMedium
val Typography.body: TextStyle get() = bodyLarge
val Typography.subheadline: TextStyle get() = bodyMedium
val Typography.footnote: TextStyle get() = bodySmall
val Typography.caption1: TextStyle get() = labelMedium
val Typography.caption2: TextStyle get() = labelSmall

private fun scheme(c: AppColors) = (if (c.dark) darkColorScheme() else lightColorScheme()).copy(
    primary = c.accent, onPrimary = Color.White, primaryContainer = c.accent, onPrimaryContainer = Color.White,
    secondary = c.secondaryLabel, onSecondary = Color.White, secondaryContainer = c.fill, onSecondaryContainer = c.label,
    tertiary = c.green, onTertiary = Color.White,
    background = c.background, onBackground = c.label, surface = c.background, onSurface = c.label,
    surfaceVariant = c.fill, onSurfaceVariant = c.secondaryLabel,
    surfaceContainerLowest = c.cell, surfaceContainerLow = c.cell, surfaceContainer = c.cell,
    surfaceContainerHigh = c.cell, surfaceContainerHighest = c.cell, surfaceTint = Color.Transparent,
    inverseSurface = c.label, inverseOnSurface = c.background, inversePrimary = c.accent,
    outline = c.separator, outlineVariant = c.separator, scrim = Color(0x66000000),
    error = c.red, onError = Color.White, errorContainer = c.red, onErrorContainer = Color.White,
)

private val LightScheme = scheme(LightColors)
private val DarkScheme = scheme(DarkColors)

/**
 * Tema urmeaza telefonul. Atingerea nu mai lasa unda Material: randul apasat se face gri, ca pe iPhone,
 * iar listele nu se mai intind la capat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = IosTypography) {
            CompositionLocalProvider(
                LocalRippleConfiguration provides null,
                LocalIndication provides PressHighlight(if (dark) Color(0x26FFFFFF) else Color(0x1F000000)),
                LocalOverscrollFactory provides null,
                LocalContentColor provides colors.label,
                content = content,
            )
        }
    }
}

object AppTheme {
    val colors: AppColors
        @Composable @ReadOnlyComposable get() = LocalAppColors.current
}
