package ro.safetyplease.core.map.pmtiles

// Growable primitive arrays and byte cursors: a country extract holds 10^5–10^6 tiles, so no boxed collections.

private fun grown(size: Int, needed: Int): Int {
    val next = size + (size shr 1) + 16
    return if (next < needed || next < 0) needed else next
}

internal class LongList(capacity: Int = 16) {
    var array = LongArray(capacity)
        private set
    var size = 0
        private set

    fun add(value: Long) {
        if (size == array.size) array = array.copyOf(grown(size, size + 1))
        array[size++] = value
    }

    operator fun get(i: Int): Long = array[i]

    fun clear() {
        size = 0
    }
}

internal class IntList(capacity: Int = 16) {
    var array = IntArray(capacity)
        private set
    var size = 0
        private set

    fun add(value: Int) {
        if (size == array.size) array = array.copyOf(grown(size, size + 1))
        array[size++] = value
    }

    operator fun get(i: Int): Int = array[i]

    fun clear() {
        size = 0
    }

    fun toArray(): IntArray = array.copyOf(size)
}

internal class DoubleList(capacity: Int = 16) {
    var array = DoubleArray(capacity)
        private set
    var size = 0
        private set

    fun add(value: Double) {
        if (size == array.size) array = array.copyOf(grown(size, size + 1))
        array[size++] = value
    }

    operator fun get(i: Int): Double = array[i]

    fun sort() = array.sort(0, size)

    fun clear() {
        size = 0
    }
}

/** Bytes appended to a growing array: unsigned LEB128 varints, as PMTiles directories store numbers, or raw. */
internal class ByteOutput(capacity: Int = 256) {
    private var buffer = ByteArray(capacity)
    var size = 0
        private set

    fun varint(value: Long) {
        ensure(10)
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            buffer[size++] = ((v and 0x7f) or 0x80).toByte()
            v = v ushr 7
        }
        buffer[size++] = v.toByte()
    }

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buffer, size)
        size += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) buffer = buffer.copyOf(grown(buffer.size, size + extra))
    }
}

/** Reads varints from [bytes] between [pos] and [end]. */
internal class ByteInput(private val bytes: ByteArray, var pos: Int, private val end: Int) {
    val remaining: Int get() = end - pos

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end) throw PmtilesException("directory ends inside a number")
            val b = bytes[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift >= 64) throw PmtilesException("number longer than 64 bits")
        }
    }
}

internal fun ByteArray.longLe(at: Int): Long {
    var v = 0L
    for (i in 7 downTo 0) v = (v shl 8) or (this[at + i].toLong() and 0xff)
    return v
}

internal fun ByteArray.intLe(at: Int): Int {
    var v = 0
    for (i in 3 downTo 0) v = (v shl 8) or (this[at + i].toInt() and 0xff)
    return v
}

internal fun ByteArray.putLongLe(at: Int, value: Long) {
    for (i in 0 until 8) this[at + i] = (value ushr (8 * i)).toByte()
}

internal fun ByteArray.putIntLe(at: Int, value: Int) {
    for (i in 0 until 4) this[at + i] = (value ushr (8 * i)).toByte()
}

/** Index of [value] in the ascending [array] up to [size], or -1. */
internal fun indexIn(array: LongArray, size: Int, value: Long): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val v = array[mid]
        when {
            v < value -> lo = mid + 1
            v > value -> hi = mid - 1
            else -> return mid
        }
    }
    return -1
}

internal fun Long.toIntOrThrow(what: String): Int {
    if (this < 0 || this > Int.MAX_VALUE) throw PmtilesException("$what of $this bytes")
    return toInt()
}
