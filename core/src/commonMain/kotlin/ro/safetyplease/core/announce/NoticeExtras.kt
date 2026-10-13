package ro.safetyplease.core.announce

import ro.safetyplease.core.protocol.WireReader
import ro.safetyplease.core.protocol.WireWriter
import ro.safetyplease.core.protocol.parseOrNull
import ro.safetyplease.core.venue.Venue

/**
 * A set of the venue's zones by their place in the venue's zone list, with that list's length [count], so a phone with
 * another list can tell it isn't meant for its own. Bit `i` of the bitmap, most significant first, is zone `i`.
 */
data class ZoneSet(val count: Int, val indices: Set<Int>) {
    init {
        require(count in 0..MAX_ZONES && indices.all { it in 0 until count })
    }

    /** The ids in [venue]; null when the venue has another number of zones. */
    fun ids(venue: Venue): Set<String>? = if (venue.zones.size != count) null else indices.map { venue.zones[it].id }.toSet()

    companion object {
        /** One byte counts the zones. */
        const val MAX_ZONES = 0xFF

        /** The zones of [venue] named in [ids]; ids it doesn't have are left out. */
        fun of(venue: Venue, ids: Collection<String>): ZoneSet =
            ZoneSet(venue.zones.size, venue.zones.indices.filter { venue.zones[it].id in ids }.toSet())
    }
}

/** The state the control console gives a zone, two bits on the wire. */
enum class ZoneState(val code: Int) {
    OPEN(0),
    CROWDED(1),
    CLOSED(2),
    EXIT_ONLY(3),
    ;

    companion object {
        fun of(code: Int): ZoneState = entries.first { it.code == (code and 0x03) }
    }
}

/**
 * The fields of a notice's extensions (PROTOCOL.md, section 6.8), behind flag [Notice.EXTRAS]: entries of
 * `type u8, len u8, value`, signed with the rest of the notice. [zones] is who the notice is for (null: everyone); [plan] the evacuation plan an evacuation alert
 * starts; [states] the map state of [NoticeTopic.MAP_STATE], one per zone in the venue's order. A reader skips a type it
 * doesn't know by its length and ignores an entry it can't read.
 */
data class NoticeExtras(val zones: ZoneSet? = null, val plan: Int? = null, val states: List<ZoneState>? = null) {
    init {
        require(plan == null || plan in 1..0xFF)
        require(states == null || states.size <= ZoneSet.MAX_ZONES)
    }

    fun encode(): ByteArray {
        val w = WireWriter(64)
        zones?.let { set ->
            val bitmap = ByteArray((set.count + 7) / 8)
            set.indices.forEach { bitmap[it / 8] = (bitmap[it / 8].toInt() or (0x80 ushr (it % 8))).toByte() }
            w.u8(ZONES).u8(1 + bitmap.size).u8(set.count).bytes(bitmap)
        }
        plan?.let { w.u8(PLAN).u8(1).u8(it) }
        states?.let { list ->
            val packed = ByteArray((list.size + 3) / 4)
            list.forEachIndexed { i, state -> packed[i / 4] = (packed[i / 4].toInt() or (state.code shl (6 - 2 * (i % 4)))).toByte() }
            w.u8(MAP_STATE).u8(1 + packed.size).u8(list.size).bytes(packed)
        }
        return w.toByteArray()
    }

    companion object {
        val NONE = NoticeExtras()

        const val ZONES = 1
        const val PLAN = 2
        const val MAP_STATE = 3

        fun decode(bytes: ByteArray): NoticeExtras {
            var extras = NONE
            var at = 0
            while (bytes.size - at >= 2) {
                val type = bytes[at].toInt() and 0xFF
                val size = bytes[at + 1].toInt() and 0xFF
                if (bytes.size - at - 2 < size) break
                val value = WireReader(bytes.copyOfRange(at + 2, at + 2 + size))
                extras = parseOrNull { extras.with(type, value) } ?: extras
                at += 2 + size
            }
            return extras
        }

        private fun NoticeExtras.with(type: Int, v: WireReader): NoticeExtras = when (type) {
            ZONES -> {
                val count = v.u8()
                val bitmap = v.bytes((count + 7) / 8)
                copy(zones = ZoneSet(count, (0 until count).filter { bitmap[it / 8].toInt() and (0x80 ushr (it % 8)) != 0 }.toSet()))
            }
            PLAN -> v.u8().let { if (it == 0) this else copy(plan = it) }
            MAP_STATE -> {
                val count = v.u8()
                val packed = v.bytes((count + 3) / 4)
                copy(states = List(count) { ZoneState.of(packed[it / 4].toInt() shr (6 - 2 * (it % 4))) })
            }
            else -> this
        }
    }
}
