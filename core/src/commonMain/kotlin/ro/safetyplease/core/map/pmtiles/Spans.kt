package ro.safetyplease.core.map.pmtiles

import okio.EOFException
import kotlin.math.max

/** Byte ranges of an archive, ascending and apart: what a download asks the host for, one request each. */
class Spans internal constructor(private val offsets: LongArray, private val lengths: IntArray, val size: Int) {
    private val packed = LongArray(size + 1)

    init {
        for (i in 0 until size) packed[i + 1] = packed[i] + lengths[i]
    }

    /** All the spans' bytes. */
    val bytes: Long get() = packed[size]

    fun offset(i: Int): Long = offsets[index(i)]

    fun length(i: Int): Int = lengths[index(i)]

    /** Where span [i] starts once all the spans are stored back to back. */
    fun packedOffset(i: Int): Long = packed[index(i)]

    /** The last span starting at or before [offset], or -1. */
    fun floor(offset: Long): Int {
        var lo = 0
        var hi = size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (offsets[mid] <= offset) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    private fun index(i: Int): Int {
        if (i < 0 || i >= size) throw IndexOutOfBoundsException("span $i of $size")
        return i
    }

    companion object {
        /** Bytes between two tiles that one request still fetches rather than ask twice. */
        const val MAX_GAP = 64L * 1024

        /** Most bytes one request asks for. */
        const val MAX_SPAN = 8L * 1024 * 1024

        /** Spans kept elsewhere, such as a download's journal: ascending, apart, none empty. */
        fun of(offsets: LongArray, lengths: IntArray): Spans {
            require(offsets.size == lengths.size) { "${offsets.size} offsets, ${lengths.size} lengths" }
            for (i in offsets.indices) {
                require(offsets[i] >= 0 && lengths[i] > 0) { "span $i at ${offsets[i]}+${lengths[i]}" }
                require(i == 0 || offsets[i] >= offsets[i - 1] + lengths[i - 1]) { "span $i overlaps the one before" }
            }
            return Spans(offsets.copyOf(), lengths.copyOf(), offsets.size)
        }
    }
}

/**
 * Merges ranges sorted by offset into spans: the next range joins the current span when the gap before it is at
 * most [maxGap] bytes and the span stays within [maxSpan] bytes. A range longer than [maxSpan] is a span of its own.
 */
internal fun coalesce(offsets: LongArray, lengths: IntArray, count: Int, maxGap: Long, maxSpan: Long): Spans {
    val starts = LongList()
    val sizes = IntList()
    var start = -1L
    var end = -1L
    for (i in 0 until count) {
        val from = offsets[i]
        val to = from + lengths[i]
        if (start >= 0 && from - end <= maxGap && max(end, to) - start <= maxSpan) {
            end = max(end, to)
        } else {
            if (start >= 0) {
                starts.add(start)
                sizes.add((end - start).toIntOrThrow("span"))
            }
            start = from
            end = to
        }
    }
    if (start >= 0) {
        starts.add(start)
        sizes.add((end - start).toIntOrThrow("span"))
    }
    return Spans(starts.array, sizes.array, starts.size)
}

/**
 * The [spans] of a plan stored back to back in [packed], as a download keeps them, read by their offsets in the
 * source archive, which is what a plan's entries hold.
 */
class PackedSource(private val spans: Spans, private val packed: RangeSource) : RangeSource {
    override val size: Long = if (spans.size == 0) 0L else spans.offset(spans.size - 1) + spans.length(spans.size - 1)

    override suspend fun read(offset: Long, length: Int): ByteArray {
        checkRange(offset, length)
        var k = spans.floor(offset)
        if (k >= 0 && offset + length <= spans.offset(k) + spans.length(k)) {
            return packed.read(spans.packedOffset(k) + (offset - spans.offset(k)), length)
        }
        // across spans: only where they touch, since a gap between two spans was never fetched
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val at = offset + done
            if (k < 0 || k >= spans.size || at < spans.offset(k) || at >= spans.offset(k) + spans.length(k)) {
                throw EOFException("source bytes at $at were not fetched")
            }
            val n = minOf(length - done.toLong(), spans.offset(k) + spans.length(k) - at).toInt()
            packed.read(spans.packedOffset(k) + (at - spans.offset(k)), n).copyInto(out, done)
            done += n
            k++
        }
        return out
    }
}
