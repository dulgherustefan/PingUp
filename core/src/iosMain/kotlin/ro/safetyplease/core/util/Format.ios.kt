package ro.safetyplease.core.util

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.snprintf

/** C's printf, which always writes a point: the iOS app doesn't call setlocale. */
@OptIn(ExperimentalForeignApi::class)
actual fun formatDecimal(value: Double, decimals: Int): String = memScoped {
    val size = 64
    val buffer = allocArray<ByteVar>(size)
    snprintf(buffer, size.convert(), "%.*f", decimals, value)
    buffer.toKString()
}
