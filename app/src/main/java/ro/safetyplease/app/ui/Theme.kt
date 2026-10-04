package ro.safetyplease.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.Severity

object Palette {
    val Signal = Color(0xFFFF6B4A)
    val Mesh = Color(0xFF43D9BD)
    val Low = Color(0xFF4CC38A)
    val Medium = Color(0xFFF5A524)
    val Urgent = Color(0xFFFF4D4D)
    val Muted = Color(0xFF8B93A1)

    fun severity(severity: Int): Color = when (severity) {
        Severity.URGENT -> Urgent
        Severity.MEDIUM -> Medium
        else -> Low
    }

    fun status(status: Int): Color = when (status) {
        AckStatus.RESOLVED -> Low
        AckStatus.ACKNOWLEDGED -> Mesh
        AckStatus.RECEIVED -> Medium
        else -> Muted
    }
}

private val Colors = darkColorScheme(
    primary = Palette.Signal,
    onPrimary = Color(0xFF2B0A02),
    primaryContainer = Color(0xFF5A1C0D),
    onPrimaryContainer = Color(0xFFFFDAD1),
    secondary = Palette.Mesh,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF12403A),
    onSecondaryContainer = Color(0xFFB5F2E6),
    tertiary = Palette.Medium,
    onTertiary = Color(0xFF3B2700),
    background = Color(0xFF0D0F13),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF0D0F13),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFFA7ADB8),
    surfaceContainerLowest = Color(0xFF090B0E),
    surfaceContainerLow = Color(0xFF12151A),
    surfaceContainer = Color(0xFF161920),
    surfaceContainerHigh = Color(0xFF1E222B),
    surfaceContainerHighest = Color(0xFF272C37),
    outline = Color(0xFF3A404C),
    outlineVariant = Color(0xFF272C37),
    error = Palette.Urgent,
    onError = Color(0xFF2B0000),
)

private val AppTypography = Typography().let {
    it.copy(
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = it.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = it.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
