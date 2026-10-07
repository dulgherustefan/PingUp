package ro.safetyplease.core.protocol

import java.io.ByteArrayOutputStream

class MalformedException(message: String) : Exception(message)

/** Cititor big-endian cu verificare de limite; orice depasire devine [MalformedException]. */
class WireReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining: Int get() = data.size - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw MalformedException("truncated")
    }

    fun u8(): Int {
        need(1)
        return data[pos++].toInt() and 0xff
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

    fun i32(): Int = u32().toInt()

    fun i64(): Long = (u32() shl 32) or u32()

    fun bytes(n: Int): ByteArray {
        need(n)
        return data.copyOfRange(pos, pos + n).also { pos += n }
    }

    fun rest(): ByteArray = bytes(remaining)

    fun string(n: Int, maxBytes: Int): String {
        if (n > maxBytes) throw MalformedException("string too long")
        return String(bytes(n), Charsets.UTF_8)
    }

    fun expectEnd() {
        if (remaining != 0) throw MalformedException("trailing bytes")
    }
}

class WireWriter(capacity: Int = 64) {
    private val out = ByteArrayOutputStream(capacity)

    fun u8(v: Int) = apply { out.write(v and 0xff) }

    fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }

    fun u32(v: Long) = apply { u16((v ushr 16).toInt() and 0xffff); u16(v.toInt() and 0xffff) }

    fun i32(v: Int) = u32(v.toLong() and 0xffffffffL)

    fun i64(v: Long) = apply { u32(v ushr 32 and 0xffffffffL); u32(v and 0xffffffffL) }

    fun bytes(b: ByteArray) = apply { out.write(b, 0, b.size) }

    fun toByteArray(): ByteArray = out.toByteArray()
}

inline fun <T> parseOrNull(block: () -> T): T? = try {
    block()
} catch (_: MalformedException) {
    null
}
