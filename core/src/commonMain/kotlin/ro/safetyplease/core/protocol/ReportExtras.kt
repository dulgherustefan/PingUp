package ro.safetyplease.core.protocol

import ro.safetyplease.core.util.validLatLon
import kotlin.math.roundToInt

/**
 * Where a missing person was last seen, from a position they had shared with the reporter: [ageMin] minutes before the
 * report's timestamp, [accuracy] an [AccuracyClass] code.
 */
data class LastSeen(val lat: Double, val lon: Double, val ageMin: Int, val accuracy: Int) {
    init {
        require(ageMin in 0..0xFFFF && accuracy in 0..AccuracyClass.UNKNOWN)
    }
}

/**
 * The report body's extension block (PROTOCOL.md, section 6.3), behind flag `0x80`: entries of `type u8, len u8, value`
 * in any order, up to the end of the body. A reader skips a type it doesn't know by its length, reads a known type's
 * value from its start (a newer version may append to it), ignores an entry too short for what it reads and stops at
 * one that runs past the body. [happenedMinAgo] is how long before the report's timestamp it happened (0: now),
 * [density] the staff's crowd level 1..5 (0: none).
 */
data class ReportExtras(
    val lastSeen: LastSeen? = null,
    val happenedMinAgo: Int = 0,
    val anonymous: Boolean = false,
    val witness: Boolean = false,
    val density: Int = 0,
    val cannotMove: Boolean = false,
    val manDown: Boolean = false,
    val unconscious: Boolean = false,
) {
    init {
        require(happenedMinAgo in 0..MAX_HAPPENED_MIN && density in 0..MAX_DENSITY)
    }

    val isEmpty: Boolean get() = this == NONE

    fun encode(): ByteArray {
        val w = WireWriter(32)
        lastSeen?.let { w.u8(LAST_SEEN).u8(LAST_SEEN_SIZE).coord(it.lat).coord(it.lon).u16(it.ageMin).u8(it.accuracy) }
        if (happenedMinAgo > 0) w.u8(HAPPENED).u8(2).u16(happenedMinAgo)
        if (anonymous) w.u8(ANONYMOUS).u8(0)
        if (witness) w.u8(WITNESS).u8(0)
        if (density > 0) w.u8(DENSITY).u8(1).u8(density)
        if (cannotMove) w.u8(CANNOT_MOVE).u8(0)
        if (manDown) w.u8(MAN_DOWN).u8(0)
        if (unconscious) w.u8(UNCONSCIOUS).u8(0)
        return w.toByteArray()
    }

    companion object {
        val NONE = ReportExtras()

        const val LAST_SEEN = 1
        const val HAPPENED = 2
        const val ANONYMOUS = 3
        const val WITNESS = 4
        const val DENSITY = 5
        const val CANNOT_MOVE = 6
        const val MAN_DOWN = 7
        const val UNCONSCIOUS = 8

        /** A report written later tells of the last 24 hours at most. */
        const val MAX_HAPPENED_MIN = 24 * 60
        const val MAX_DENSITY = 5
        private const val LAST_SEEN_SIZE = 11

        /** Never rejects: what this version can't read in the block is left out. */
        fun decode(block: ByteArray): ReportExtras {
            var extras = NONE
            var at = 0
            while (block.size - at >= 2) {
                val type = block[at].toInt() and 0xFF
                val size = block[at + 1].toInt() and 0xFF
                if (block.size - at - 2 < size) break
                val value = WireReader(block.copyOfRange(at + 2, at + 2 + size))
                extras = parseOrNull { extras.with(type, value) } ?: extras
                at += 2 + size
            }
            return extras
        }

        private fun ReportExtras.with(type: Int, v: WireReader): ReportExtras = when (type) {
            LAST_SEEN -> {
                val lat = v.coord()
                val lon = v.coord()
                val age = v.u16()
                val accuracy = v.u8()
                // a place off the earth counts as none: nobody can be sent there
                if (!validLatLon(lat, lon)) this
                else copy(lastSeen = LastSeen(lat, lon, age, if (accuracy > AccuracyClass.UNKNOWN) AccuracyClass.UNKNOWN else accuracy))
            }
            HAPPENED -> v.u16().let { if (it in 1..MAX_HAPPENED_MIN) copy(happenedMinAgo = it) else this }
            ANONYMOUS -> copy(anonymous = true)
            WITNESS -> copy(witness = true)
            DENSITY -> v.u8().let { if (it in 1..MAX_DENSITY) copy(density = it) else this }
            CANNOT_MOVE -> copy(cannotMove = true)
            MAN_DOWN -> copy(manDown = true)
            UNCONSCIOUS -> copy(unconscious = true)
            else -> this
        }

        private fun WireWriter.coord(deg: Double) = i32((deg * 1e6).roundToInt())

        private fun WireReader.coord(): Double = i32() / 1e6
    }
}
