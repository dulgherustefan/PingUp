package ro.safetyplease.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.Severity

/** Culorile care nu tin de tema: bara de jos arata la fel pe luminos si pe intunecat. */
object Brand {
    val Sage = Color(0xFFD3D8B2)
    val OnSage = Color(0xFF1A1F1C)
    val Forest = Color(0xFF2B5E45)
    val Bar = Color(0xFF1F2421)
    val BarMuted = Color(0xFF9EA59F)
}

@Immutable
class AppColors(
    val dark: Boolean,
    val background: Color,
    val card: Color,
    val cardHigh: Color,
    val text: Color,
    val textSecondary: Color,
    val hairline: Color,
    /** „Ales” si actiunea principala: salvie pe intunecat, verde padure pe luminos (salvia nu are contrast pe alb). */
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val forest: Color,
    val onForest: Color,
    val bubbleOther: Color,
    val avatar: Color,
    val onAvatar: Color,
    val ok: Color,
    val wait: Color,
    val danger: Color,
    /** Tonuri stinse pentru zonele de pe harta, in ordinea din venue.json. */
    val zones: List<Color>,
) {
    fun severity(severity: Int): Color = if (severity == Severity.URGENT) danger else wait

    fun status(status: Int): Color = when (status) {
        AckStatus.RESOLVED, AckStatus.ACKNOWLEDGED -> ok
        else -> wait
    }
}

private val DarkColors = AppColors(
    dark = true,
    background = Color(0xFF151816),
    card = Color(0xFF1F2421),
    cardHigh = Color(0xFF29302B),
    text = Color(0xFFF1F3EE),
    textSecondary = Color(0xFF9EA59F),
    hairline = Color(0x14F1F3EE),
    accent = Brand.Sage,
    onAccent = Brand.OnSage,
    accentSoft = Color(0x1FD3D8B2),
    forest = Brand.Forest,
    onForest = Color.White,
    bubbleOther = Color(0xFF29302B),
    avatar = Color(0xFF29302B),
    onAvatar = Color(0xFFC9CFC6),
    ok = Color(0xFF30D158),
    wait = Color(0xFFFF9F0A),
    danger = Color(0xFFFF6961),
    zones = listOf(
        Color(0xFF8FB89B), Color(0xFFA9A3C9), Color(0xFFD6B77A), Color(0xFF7FA9C4),
        Color(0xFF7CC4A4), Color(0xFF9AA39C), Color(0xFFB5C48A), Color(0xFF86BDB8),
    ),
)

private val LightColors = AppColors(
    dark = false,
    background = Color(0xFFF5F6F2),
    card = Color.White,
    cardHigh = Color(0xFFEEF0EA),
    text = Color(0xFF1A1F1C),
    textSecondary = Color(0xFF5F6660),
    hairline = Color(0x141A1F1C),
    accent = Brand.Forest,
    onAccent = Color.White,
    accentSoft = Color(0xFFE6E9D3),
    forest = Brand.Forest,
    onForest = Color.White,
    bubbleOther = Color(0xFFEEF0EA),
    avatar = Color(0xFFE6E9D3),
    onAvatar = Color(0xFF1A1F1C),
    ok = Color(0xFF1B7A36),
    wait = Color(0xFFA84B00),
    danger = Color(0xFFC4211B),
    zones = listOf(
        Color(0xFF4E8A63), Color(0xFF7B72B0), Color(0xFFB08A3A), Color(0xFF4A7FA3),
        Color(0xFF3E9A78), Color(0xFF7A837C), Color(0xFF8A9A4E), Color(0xFF4C9892),
    ),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

private fun AppColors.material() = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = forest, onPrimaryContainer = onForest,
        secondary = forest, onSecondary = onForest, secondaryContainer = cardHigh, onSecondaryContainer = text,
        tertiary = accent, onTertiary = onAccent,
        background = background, onBackground = text, surface = background, onSurface = text,
        surfaceVariant = cardHigh, onSurfaceVariant = textSecondary,
        surfaceContainerLowest = background, surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = cardHigh, surfaceContainerHighest = cardHigh,
        outline = textSecondary, outlineVariant = Color(0xFF333B36),
        error = danger, onError = Color(0xFF2B0000),
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = text,
        secondary = forest, onSecondary = onForest, secondaryContainer = cardHigh, onSecondaryContainer = text,
        tertiary = accent, onTertiary = onAccent,
        background = background, onBackground = text, surface = background, onSurface = text,
        surfaceVariant = cardHigh, onSurfaceVariant = textSecondary,
        surfaceContainerLowest = card, surfaceContainerLow = card, surfaceContainer = card,
        surfaceContainerHigh = cardHigh, surfaceContainerHighest = cardHigh,
        outline = textSecondary, outlineVariant = Color(0xFFDDE0D8),
        error = danger, onError = Color.White,
    )
}

/** Scara de text: putine marimi, titlurile SemiBold, restul Regular sau Medium. Roboto, cu ș si ț corecte. */
private val AppTypography = Typography().let {
    it.copy(
        displaySmall = TextStyle(fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
        bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

val CardShape = RoundedCornerShape(20.dp)

/** Tema urmeaza telefonul; fara culori dinamice. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalReduceMotion provides rememberReduceMotion(),
    ) {
        MaterialTheme(colorScheme = colors.material(), typography = AppTypography, shapes = AppShapes, content = content)
    }
}
