package ro.safetyplease.core.util

import java.util.Locale

actual fun formatDecimal(value: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", value)
