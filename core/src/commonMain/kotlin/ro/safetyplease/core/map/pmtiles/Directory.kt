package ro.safetyplease.core.map.pmtiles

import okio.Buffer
import okio.GzipSink
import okio.GzipSource
import okio.IOException
import okio.buffer
import okio.use

/**
 * A PMTiles directory, entries sorted by tile id. An entry is a run of [runLength] consecutive tile ids that all have
 * the [length] bytes at [offset], or, when its run length is 0, a leaf directory of [length] bytes at [offset]. In an
 * archive the offsets count from the tile data or the leaf directories section; in an [ExtractPlan] from the start of
 * the source file.
 */
class Directory internal constructor(
    private val tileIds: LongArray,
    private val runLengths: IntArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    val size: Int,
) {
    fun tileId(i: Int): Long = tileIds[index(i)]

    /** Unsigned 32 bits in the format, hence a Long. */
    fun runLength(i: Int): Long = runLengths[index(i)].toLong() and 0xffffffffL

    fun offset(i: Int): Long = offsets[index(i)]

    fun length(i: Int): Int = lengths[index(i)]

    fun isLeaf(i: Int): Boolean = runLengths[index(i)] == 0

    /** The last entry whose tile id is at most [tileId], or -1 when they all start past it. */
    fun floor(tileId: Long): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tileIds[mid] <= tileId) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /**
     * Entries [from] until [to] in the format, uncompressed: their count, then the tile ids as deltas, the run lengths,
     * the lengths, and the offsets, each as offset + 1 or 0 when it follows the previous entry's bytes.
     */
    fun encode(from: Int = 0, to: Int = size): ByteArray {
        require(from in 0..to && to <= size) { "entries $from until $to of $size" }
        val out = ByteOutput((to - from) * 8 + 10)
        out.varint((to - from).toLong())
        var last = 0L
        for (i in from until to) {
            out.varint(tileIds[i] - last)
            last = tileIds[i]
        }
        for (i in from until to) out.varint(runLengths[i].toLong() and 0xffffffffL)
        for (i in from until to) out.varint(lengths[i].toLong())
        for (i in from until to) {
            if (i > from && offsets[i] == offsets[i - 1] + lengths[i - 1]) out.varint(0) else out.varint(offsets[i] + 1)
        }
        return out.toByteArray()
    }

    /** The same entries with other [offsets], the arrays shared. */
    internal fun withOffsets(offsets: LongArray): Directory {
        require(offsets.size >= size)
        return Directory(tileIds, runLengths, offsets, lengths, size)
    }

    private fun index(i: Int): Int {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("entry $i of $size")
        return i
    }

    companion object {
        /** The uncompressed directory in [bytes] from [from] until [to]. */
        fun decode(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Directory {
            val input = ByteInput(bytes, from, to)
            val count = input.varint()
            // every entry takes at least four bytes, so a corrupt count can't make huge arrays
            if (count < 0 || count > input.remaining / 4) throw PmtilesException("$count entries in ${to - from} bytes")
            val n = count.toInt()
            val tileIds = LongArray(n)
            val runLengths = IntArray(n)
            val lengths = IntArray(n)
            val offsets = LongArray(n)
            var last = 0L
            for (i in 0 until n) {
                val id = last + input.varint()
                if (id < last) throw PmtilesException("tile id past 2^63")
                tileIds[i] = id
                last = id
            }
            for (i in 0 until n) {
                val run = input.varint()
                if (run < 0 || run > 0xffffffffL) throw PmtilesException("run length $run")
                runLengths[i] = run.toInt()
            }
            for (i in 0 until n) lengths[i] = input.varint().toIntOrThrow("entry")
            for (i in 0 until n) {
                val value = input.varint()
                offsets[i] = when {
                    value == 0L && i > 0 -> offsets[i - 1] + lengths[i - 1]
                    value <= 0L -> throw PmtilesException("offset $value of entry $i")
                    else -> value - 1
                }
            }
            return Directory(tileIds, runLengths, offsets, lengths, n)
        }
    }
}

/** Builds a [Directory] entry by entry, tile ids ascending; [build] takes the arrays, so the builder is done then. */
class DirectoryBuilder(capacity: Int = 64) {
    private var tileIds = LongArray(capacity)
    private var runLengths = IntArray(capacity)
    private var offsets = LongArray(capacity)
    private var lengths = IntArray(capacity)
    private var built = false
    var size = 0
        private set

    fun add(tileId: Long, runLength: Long, offset: Long, length: Int): DirectoryBuilder {
        check(!built) { "already built" }
        require(tileId >= 0 && runLength in 0..0xffffffffL && offset >= 0 && length >= 0) {
            "entry $tileId, $runLength, $offset, $length"
        }
        require(size == 0 || tileId > tileIds[size - 1]) { "tile id $tileId after ${tileIds[size - 1]}" }
        if (size == tileIds.size) {
            val capacity = size + (size shr 1) + 16
            tileIds = tileIds.copyOf(capacity)
            runLengths = runLengths.copyOf(capacity)
            offsets = offsets.copyOf(capacity)
            lengths = lengths.copyOf(capacity)
        }
        tileIds[size] = tileId
        runLengths[size] = runLength.toInt()
        offsets[size] = offset
        lengths[size] = length
        size++
        return this
    }

    /** Adds [tileId] to the last entry when it is the next id of that run with the same bytes; false otherwise. */
    internal fun extend(tileId: Long, offset: Long, length: Int): Boolean {
        val last = size - 1
        if (last < 0 || runLengths[last] == 0 || offsets[last] != offset || lengths[last] != length) return false
        val run = runLengths[last].toLong() and 0xffffffffL
        if (tileIds[last] + run != tileId || run == 0xffffffffL) return false
        runLengths[last] = (run + 1).toInt()
        return true
    }

    fun build(): Directory {
        check(!built) { "already built" }
        built = true
        return Directory(tileIds, runLengths, offsets, lengths, size)
    }
}

/** Largest directory or metadata accepted once inflated, against a corrupt or hostile archive. */
internal const val MAX_INFLATED = 64L * 1024 * 1024

/** The directory in [length] bytes of [bytes] from [from], compressed with [compression]. */
internal fun decodeDirectory(bytes: ByteArray, from: Int, length: Int, compression: Int): Directory =
    if (compression == Compression.NONE) {
        Directory.decode(bytes, from, from + length)
    } else {
        Directory.decode(inflate(bytes, from, length, compression))
    }

internal fun inflate(bytes: ByteArray, from: Int, length: Int, compression: Int): ByteArray = when (compression) {
    Compression.NONE -> bytes.copyOfRange(from, from + length)
    Compression.GZIP -> {
        val out = Buffer()
        try {
            GzipSource(Buffer().write(bytes, from, length)).use { source ->
                while (source.read(out, 64L * 1024) != -1L) {
                    if (out.size > MAX_INFLATED) throw PmtilesException("inflates past $MAX_INFLATED bytes")
                }
            }
        } catch (e: PmtilesException) {
            throw e
        } catch (e: IOException) {
            throw PmtilesException("bad gzip: ${e.message}")
        }
        out.readByteArray()
    }
    else -> throw PmtilesException("compression $compression not supported")
}

internal fun deflate(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
    Compression.NONE -> bytes
    Compression.GZIP -> {
        val out = Buffer()
        GzipSink(out).buffer().use { it.write(bytes) }
        out.readByteArray()
    }
    else -> throw IllegalArgumentException("compression $compression not supported")
}
