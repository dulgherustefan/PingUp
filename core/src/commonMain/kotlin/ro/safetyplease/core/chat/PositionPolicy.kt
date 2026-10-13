package ro.safetyplease.core.chat

import ro.safetyplease.core.event.EventPack
import ro.safetyplease.core.protocol.PositionPoint
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * What two phones must share to read each other's positions (PROTOCOL.md, section 6.6): the grid box and the day
 * start, both from the event pack. Without a box every position travels in degrees.
 */
class PositionFrame(val box: PositionBox?, val dayStartMs: Long) {

    fun fixMinute(timeMs: Long): Int = (timeMs - dayStartMs).floorDiv(MINUTE_MS).mod(MINUTES)

    /** The fix time a minute stands for: of all the times it can mean, 45 days apart, the one nearest [nowMs]. */
    fun fixTime(minute: Int, nowMs: Long): Long {
        val period = MINUTES * MINUTE_MS
        val base = dayStartMs + minute * MINUTE_MS
        val turns = (nowMs - base + period / 2).floorDiv(period)
        return base + turns * period
    }

    /**
     * The point to send, after masking: exact is to the metre, approximate is the centre of a 128 m cell (the low 7 bits
     * of each grid axis, or a cell of the same size in micro-degrees outside the box).
     */
    fun point(lat: Double, lon: Double, approximate: Boolean): PositionPoint {
        box?.toGrid(lat, lon)?.let { (x, y) ->
            return if (approximate) PositionPoint.Grid(cellCentre(x), cellCentre(y)) else PositionPoint.Grid(x, y)
        }
        if (!approximate) return PositionPoint.Absolute(lat, lon)
        val latMicro = centre((lat * 1e6).roundToLong(), CELL_MICRO_DEG)
        val lonStep = maxOf(1L, (CELL_MICRO_DEG / cos(radians(latMicro / 1e6))).roundToLong())
        return PositionPoint.Absolute(latMicro / 1e6, centre((lon * 1e6).roundToLong(), lonStep) / 1e6)
    }

    /** Null for a grid point without a box: this phone has no event pack to read it with. */
    fun latLon(point: PositionPoint): GeoPoint? = when (point) {
        is PositionPoint.Absolute -> GeoPoint(point.lat, point.lon)
        is PositionPoint.Grid -> box?.toLatLon(point.x, point.y)
    }

    private fun cellCentre(v: Int): Int = (v and CELL_MASK.inv()) or (CELL_MASK + 1) / 2

    private fun centre(micro: Long, step: Long): Long = micro.floorDiv(step) * step + step / 2

    companion object {
        const val MINUTE_MS = 60_000L
        const val MINUTES = 1 shl 16

        /** Low bits masked for an approximate position: a 128 m cell. */
        const val CELL_MASK = 0x7F

        /** About 128 m of latitude. */
        private const val CELL_MICRO_DEG = 1_150L

        /** Half the diagonal of a 128 m cell: how far off an approximate position may be. */
        const val APPROXIMATE_RADIUS_M = 91.0

        val NONE = PositionFrame(null, 0)

        /** The pack's box and day start; without a box in the pack, the venue bounds' corner; without a pack, the Unix epoch. */
        fun of(pack: EventPack?, bounds: Bounds?): PositionFrame =
            PositionFrame(pack?.positionBox ?: bounds?.let(PositionBox::of), (pack?.dayStart ?: 0L) * 1000)
    }
}

/**
 * When this phone sends its position while it shares it. Pure: the caller gives the time and the point after
 * masking, asks [due], and calls [sent] for what actually went.
 *
 * A move is at least [MOVE_M] from the last point sent and more than the fix's accuracy radius; it goes once
 * [intervalMs] has passed since the last send. Without a move, heartbeats go 2, 5, 10, then every 15 minutes after the
 * last send, never closer than [intervalMs]; a move starts the series again.
 */
class PositionPolicy {
    private var lastPoint: GeoPoint? = null
    private var lastAtMs = 0L
    private var beat = 0

    fun due(nowMs: Long, point: GeoPoint, accuracyM: Int?, keys: Int): Boolean {
        val last = lastPoint ?: return true
        val wait = if (isMove(last, point, accuracyM)) intervalMs(keys) else heartbeatMs(keys)
        return nowMs - lastAtMs >= wait
    }

    fun sent(nowMs: Long, point: GeoPoint, accuracyM: Int?) {
        val last = lastPoint
        beat = if (last == null || isMove(last, point, accuracyM)) 0 else minOf(beat + 1, HEARTBEATS_MS.lastIndex)
        lastPoint = point
        lastAtMs = nowMs
    }

    /** The longest a friend should wait for the next one after what was just sent: the next heartbeat. */
    fun nextUpdateMs(keys: Int): Long = heartbeatMs(keys)

    /** Before anything is sent again, as when a new session starts: the next fix goes at once. */
    fun reset() {
        lastPoint = null
        beat = 0
    }

    private fun heartbeatMs(keys: Int): Long = maxOf(HEARTBEATS_MS[beat], intervalMs(keys))

    private fun isMove(from: GeoPoint, to: GeoPoint, accuracyM: Int?): Boolean =
        distanceMeters(from, to) >= maxOf(MOVE_M, accuracyM ?: 0)

    companion object {
        const val MOVE_M = 50
        const val MIN_INTERVAL_MS = 60_000L

        /** Packets the mesh gives positions in ten seconds: 0.7 a second, 25 % of 2.8 (`PositionBudget`). */
        private const val POSITIONS_PER_10_S = 7L

        val HEARTBEATS_MS = longArrayOf(2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L)

        /**
         * T = max(60 s, K / (0.7 x B)), rounded up to the second: [keys] positions live in this phone's cache,
         * [perPacket] records a packet carries (1 without batching), so that together they stay within their share.
         */
        fun intervalMs(keys: Int, perPacket: Int = 1): Long {
            val per10 = POSITIONS_PER_10_S * perPacket
            return maxOf(MIN_INTERVAL_MS, (keys * 10L + per10 - 1) / per10 * 1000)
        }
    }
}

/**
 * At most one per [intervalMs] for each id: "Unde ești?" asked of a friend or answered to one, a "Fă-te văzut", a
 * meeting point proposed to a group.
 */
class PerFriendLimit(private val intervalMs: Long) {
    private val last = HashMap<Long, Long>()

    fun ready(id: Long, nowMs: Long): Boolean = last[id]?.let { nowMs - it >= intervalMs } ?: true

    fun tryTake(id: Long, nowMs: Long): Boolean {
        if (!ready(id, nowMs)) return false
        last[id] = nowMs
        if (last.size > MAX_IDS) last.entries.removeAll { nowMs - it.value >= intervalMs }
        return true
    }

    private companion object {
        const val MAX_IDS = 256
    }
}
