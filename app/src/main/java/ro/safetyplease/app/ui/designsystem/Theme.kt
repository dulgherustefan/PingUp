package ro.safetyplease.app.ui.designsystem

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
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
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Severity

/**
 * App colors, from the logo palette: pin green is the accent, green-black night is the dark background,
 * and the sage of the cap warms the light theme.
 */
@Immutable
class AppColors(
    val dark: Boolean,
    /** Background of lists and conversations. */
    val background: Color,
    /** Background of grouped-row screens (settings) and their cells. */
    val grouped: Color,
    val cell: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    /** Fill of fields and gray buttons. */
    val fill: Color,
    /** Pressed row. */
    val pressed: Color,
    val accent: Color,
    val red: Color,
    val green: Color,
    val orange: Color,
    /** Orange for text: the surface orange is unreadable on white in direct sunlight. */
    val orangeInk: Color,
    val bubbleIn: Color,
    /** Your bubbles: solid forest green, same in light and dark. */
    val bubbleOut: Color,
    val onBubbleOut: Color,
    val onBubbleOutSecondary: Color,
    /** Glass: tint over the blur, tint without blur (below Android 12), outline, selected tab pill. */
    val glass: Color,
    val glassSolid: Color,
    val glassRim: Color,
    val glassPill: Color,
    /** Background of dialogs and context menus. */
    val dialog: Color,
    val menu: Color,
    /** Track of a switch that's off, and the selected pill in a segmented control. */
    val switchOff: Color,
    val segmentPill: Color,
    /** Logo pin colors: bright green, forest green and the sage of the cap. */
    val brand: Color,
    val brandDeep: Color,
    val sage: Color,
    /** Content on the solid accent: white in light, green-black in dark, so it reads on bright green. */
    val onAccent: Color,
    /** Faint tint at the top of screens. */
    val glow: Color,
    /** Sender name colors in groups, one per person. */
    val names: List<Color>,
) {
    /** Avatar background and ink; the same in light and dark. */
    val avatars: List<Pair<Color, Color>> = AvatarPairs

    /**
     * Fill of a map zone, in venue.json order. Dark theme: dark zone, light name.
     * The medical zone always gets first-aid green (ISO 3864), wherever it is in the list.
     */
    fun zoneFill(index: Int, id: String): Color {
        val (light, ink) = zonePair(index, id)
        return if (dark) ink.copy(alpha = 0.34f).compositeOver(Night.cell) else light
    }

    /** Zone name drawn over [zoneFill]: above 6:1 in light, above 10:1 in dark. */
    fun zoneInk(index: Int, id: String): Color {
        val (light, ink) = zonePair(index, id)
        return if (dark) light else ink
    }

    private fun zonePair(index: Int, id: String): Pair<Color, Color> = if (id == MEDICAL_ZONE) MedicalZone else ZonePairs[index % ZonePairs.size]

    /**
     * Red for text and icons (Urgent, Cancel alert, delivery failed). In light the surface red is only 3.5:1
     * on white; this one is 5.8:1 on white and 5.2:1 on gray. In dark the surface red already passes (5.6:1).
     */
    val redInk: Color get() = if (dark) red else Color(0xFFC4261D)

    /** Selected option in a group (filter, report category). Not the solid accent, so a choice doesn't compete with the main action. */
    val selection: Color get() = if (dark) sage else brandDeep
    val onSelection: Color get() = if (dark) Night.background else Color.White

    // names kept for the demo screen
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

// avatar tones come from the logo: mint, sage, forest, khaki, earth
private val AvatarPairs = listOf(
    Color(0xFFD2F0DC) to Color(0xFF17642A),
    Color(0xFFE4E8CF) to Color(0xFF55601F),
    Color(0xFFD9E7DF) to Color(0xFF2B5E45),
    Color(0xFFCFE9E5) to Color(0xFF0F6A62),
    Color(0xFFEFE7C9) to Color(0xFF6C5A14),
    Color(0xFFE0EBC8) to Color(0xFF46651A),
    Color(0xFFDCE3DE) to Color(0xFF3E4B43),
    Color(0xFFF1DED1) to Color(0xFF8A4A24),
    Color(0xFFD6E6EE) to Color(0xFF2A5A70),
    Color(0xFFF3EBD8) to Color(0xFF7A6431),
    Color(0xFFE6EEDB) to Color(0xFF4D6B2E),
    Color(0xFFE3E3DC) to Color(0xFF57584F),
)

/** Id of the medical zone in venue.json. */
const val MEDICAL_ZONE = "medical"

/**
 * Map zones: muted earthy tones from the forest green and sage family, so the map doesn't compete with
 * colors that carry meaning. None sits near red (incidents), orange (meeting point), bright green (you)
 * or first-aid green. Built in OKLCH: fill at L 0.925, name at L 0.43.
 * Order: slate, teal, sand, olive, stone, pine, mist; the medical zone skips them.
 */
private val ZonePairs = listOf(
    Color(0xFFD9E8F6) to Color(0xFF285476),
    Color(0xFFD2ECEC) to Color(0xFF1C5A5A),
    Color(0xFFF0E6C9) to Color(0xFF604E10),
    Color(0xFFE3EACD) to Color(0xFF4C5618),
    Color(0xFFE7E6E0) to Color(0xFF515049),
    Color(0xFFDAEBE5) to Color(0xFF35584D),
    Color(0xFFDBE9ED) to Color(0xFF325660),
)

/** First-aid green (ISO 3864), only for the medical zone. */
private val MedicalZone = Color(0xFFCCF2D2) to Color(0xFF1C5F31)

private val LightColors = AppColors(
    dark = false,
    background = Color.White,
    // light gray tinted toward the sage of the logo
    grouped = Color(0xFFF1F3EC),
    cell = Color.White,
    label = Color(0xFF0E110F),
    secondaryLabel = Color(0xB83B4338),
    tertiaryLabel = Color(0x4D3B4338),
    separator = Color(0xFFD0D5C8),
    fill = Color(0x1F6F7D67),
    pressed = Color(0xFFDEE2D7),
    // green with text contrast on white (5.1:1); the bright one stays for solid surfaces
    accent = Color(0xFF1A7F37),
    red = Color(0xFFFF3B30),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9500),
    orangeInk = Color(0xFFB25000),
    bubbleIn = Color(0xFFE6EADF),
    bubbleOut = Color(0xFF2B5E45),
    onBubbleOut = Color.White,
    onBubbleOutSecondary = Color(0xCCFFFFFF),
    // glass shows a little of the blurred content passing under it
    glass = Color(0x99FFFFFF),
    glassSolid = Color(0xF5FBFCF8),
    glassRim = Color(0x1A2B5E45),
    glassPill = Color(0x2430D158),
    dialog = Color.White,
    menu = Color(0xFFF8FAF4),
    switchOff = Color(0xFFE6E9E0),
    segmentPill = Color.White,
    brand = Color(0xFF30D158),
    brandDeep = Color(0xFF2B5E45),
    sage = Color(0xFFD3D8B2),
    onAccent = Color.White,
    glow = Color(0xFFF5F7ED),
    names = listOf(
        0xFF006DA3, 0xFF067906, 0xFFB814B8, 0xFFC13215, 0xFF5B6976, 0xFFCC0066, 0xFF2E51FF, 0xFF007575,
        0xFF9C5711, 0xFFD00B4D, 0xFF8F2AF4, 0xFF3D7406, 0xFFD00B0B, 0xFF007A3D, 0xFF5151F6, 0xFF866118,
    ).map { Color(it) },
)

private val DarkColors = AppColors(
    dark = true,
    // the night of the logo background, not pure black
    background = Night.background,
    grouped = Night.background,
    cell = Night.cell,
    label = Color(0xFFF4F6F0),
    secondaryLabel = Color(0xB3E3E9DE),
    tertiaryLabel = Color(0x4DE3E9DE),
    separator = Color(0xFF2F3530),
    fill = Color(0x3D7C8A7F),
    pressed = Color(0xFF2F3530),
    accent = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    orangeInk = Color(0xFFFF9F0A),
    bubbleIn = Color(0xFF2C322D),
    bubbleOut = Color(0xFF2B5E45),
    onBubbleOut = Color(0xFFF4F6F0),
    onBubbleOutSecondary = Color(0xCCFFFFFF),
    // dark glass is lighter than the background, otherwise bars look like holes
    glass = Color(0xA3232925),
    glassSolid = Color(0xF5232925),
    glassRim = Color(0x33D3D8B2),
    glassPill = Color(0x3330D158),
    dialog = Color(0xFF222723),
    menu = Color(0xFF222723),
    switchOff = Color(0xFF343A35),
    segmentPill = Color(0xFF4A524B),
    brand = Color(0xFF30D158),
    brandDeep = Color(0xFF2B5E45),
    sage = Color(0xFFD3D8B2),
    onAccent = Night.background,
    glow = Color(0xFF151B16),
    names = listOf(
        0xFF00A7FA, 0xFF0AB80A, 0xFFF65AF6, 0xFFFF6F52, 0xFF8BA1B6, 0xFFF76EB2, 0xFF8599FF, 0xFF00B2B2,
        0xFFD5920B, 0xFFFF6B9C, 0xFFBF80FF, 0xFF5EB309, 0xFFFF7070, 0xFF00B85C, 0xFF9494FF, 0xFFD68F00,
    ).map { Color(it) },
)

/** Dark theme backgrounds, also used for map zones. */
private object Night {
    val background = Color(0xFF0E110F)
    val cell = Color(0xFF1C211D)
}

val LocalAppColors = staticCompositionLocalOf { LightColors }

/**
 * Screen background: the base color with a barely visible sage tint at the top, fading out by the first row.
 * Linear and faint on purpose (+0.04 OKLCH lightness). Depends only on width so the top bar can repeat it exactly.
 */
fun screenGlow(width: Float, base: Color, glow: Color): Brush =
    Brush.verticalGradient(0f to glow, 1f to base, startY = 0f, endY = width * 0.6f)

/** Full-screen background with the top tint. */
@Composable
fun Modifier.screenBackground(base: Color): Modifier {
    val glow = AppTheme.colors.glow
    return drawBehind { drawRect(screenGlow(size.width, base, glow)) }
}

private fun inter(opticalSize: TextUnit) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            R.font.inter_variable, weight,
            variationSettings = FontVariation.Settings(weight, FontStyle.Normal, FontVariation.opticalSizing(opticalSize)),
        )
    },
)

/** Inter: the text cut up to 22sp, the display cut above. */
val TextFont = inter(14.sp)
val DisplayFont = inter(32.sp)

private val Trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(family: FontFamily, size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = family, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
    letterSpacing = tracking.em, lineHeightStyle = Trim,
)

/**
 * iOS-style type scale (default "Large" size) mapped onto Material slots so Material components use it too.
 * The iOS names are extensions below: `typography.headline`, `typography.footnote`...
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
    primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accent, onPrimaryContainer = c.onAccent,
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
 * Theme follows the system. Touch shows a gray pressed state instead of the Material ripple,
 * and lists don't stretch at the ends.
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
