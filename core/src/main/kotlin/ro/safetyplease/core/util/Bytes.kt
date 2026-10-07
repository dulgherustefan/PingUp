package ro.safetyplease.core.util

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Used by the protocol (cancel hash) and by identity (node id). */
fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "bad hex" }
        ((hi shl 4) or lo).toByte()
    }
}

fun Long.toHex(): String = toBytes().toHex()

fun Long.shortHex(): String = toHex().take(8)

fun Int.toHex(): String = ByteBuffer.allocate(4).putInt(this).array().toHex()

fun Long.toBytes(): ByteArray = ByteBuffer.allocate(8).putLong(this).array()

fun ByteArray.toLong(offset: Int = 0): Long = ByteBuffer.wrap(this, offset, 8).long

/** First 4 bytes of the nodeId, as they appear in advertising. */
fun Long.nodePrefix(): Int = (this ushr 32).toInt()

fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)

/** Truncates to at most [maxBytes] UTF-8 bytes without splitting a character. */
fun String.truncateUtf8(maxBytes: Int): String {
    if (utf8().size <= maxBytes) return this
    var end = length
    while (end > 0) {
        if (Character.isLowSurrogate(this[end - 1]) && end > 1) {
            end--
        }
        end--
        val candidate = substring(0, end)
        if (candidate.utf8().size <= maxBytes) return candidate
    }
    return ""
}
