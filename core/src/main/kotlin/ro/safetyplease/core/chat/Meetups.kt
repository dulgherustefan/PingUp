package ro.safetyplease.core.chat

import ro.safetyplease.core.protocol.MeetingLimits
import ro.safetyplease.core.venue.GeoPoint

/** Where a meeting point is: a numbered landmark of the site plan, or a point (the venue's meeting point, or any other). */
data class MeetingPlace(val landmark: Int = 0, val point: GeoPoint? = null, val venuePoint: Boolean = false) {
    init {
        require(if (landmark == 0) point != null else landmark in 1..255 && point == null && !venuePoint)
    }

    companion object {
        fun landmark(number: Int) = MeetingPlace(landmark = number)
        fun venue(point: GeoPoint) = MeetingPlace(point = point, venuePoint = true)
        fun point(point: GeoPoint) = MeetingPlace(point = point)
    }
}

/**
 * The meeting point pinned at the top of [conversation]: who proposed it, where, at what time of day
 * ([minuteOfDay], null for none) and until when it shows. [accepted] are the members who said yes, the proposer
 * not included.
 */
data class MeetingProposal(
    val conversation: String,
    val proposer: Long,
    val msgId: Long,
    val seq: Int,
    val place: MeetingPlace,
    val minuteOfDay: Int?,
    val expiresAtMs: Long,
    val accepted: List<Long> = emptyList(),
)

/** A friend's request waiting for an answer here: "Fă-te văzut" or "Vin la tine". */
data class FriendAsk(val sender: Long, val atMs: Long, val seq: Int = 0, val precision: Int = 0, val seconds: Int = 0)

/** A friend whose torch is lit for us, until [untilMs]. */
data class Lighting(val friend: Long, val untilMs: Long)

/** The time of day of a meeting point, on the clock the phones at one festival share. */
object MeetingTime {
    /** A meeting point whose time passed by more than this when it arrives is dropped. */
    const val LATE_MIN = 60
    private const val DAY = MeetingLimits.MINUTES_PER_DAY

    /** Whether [minuteOfDay] (null: no time) is more than [LATE_MIN] before [nowMinute], both minutes after midnight, the day wrapping. */
    fun isLate(minuteOfDay: Int?, nowMinute: Int): Boolean {
        if (minuteOfDay == null) return false
        // the signed distance from now to the meeting, the nearer way round the clock
        val ahead = (minuteOfDay - nowMinute + DAY / 2).mod(DAY) - DAY / 2
        return ahead < -LATE_MIN
    }
}

/** The middle of the points known for a group: null under two, since one point is no middle. */
fun centroid(points: List<GeoPoint>): GeoPoint? {
    if (points.size < 2) return null
    return GeoPoint(points.sumOf { it.lat } / points.size, points.sumOf { it.lon } / points.size)
}

/**
 * The torch pattern of "Fă-te văzut": three flashes and a pause, under three flashes a second (the photosensitivity
 * limit of WCAG 2.3.1), for [DURATION_MS]. The phone's vibration follows the same steps.
 */
object TorchPulse {
    const val DURATION_MS = 10_000L
    const val SECONDS = (DURATION_MS / 1000).toInt()

    /** One cycle, alternately lit and dark, starting lit. */
    private val CYCLE = longArrayOf(250, 170, 250, 170, 250, 610)

    /** A lit or dark stretch of [ms]. */
    data class Step(val lit: Boolean, val ms: Long)

    /** The whole pattern, [durationMs] long in all; it ends dark, so the torch is off when it is over. */
    fun steps(durationMs: Long = DURATION_MS): List<Step> {
        val steps = ArrayList<Step>()
        var at = 0L
        var i = 0
        while (at < durationMs) {
            val lit = i % 2 == 0
            val ms = minOf(CYCLE[i % CYCLE.size], durationMs - at)
            steps += Step(lit, ms)
            at += ms
            i++
        }
        if (steps.last().lit) steps[steps.lastIndex] = steps.last().copy(lit = false)
        return steps
    }

    /** Whether the torch is lit [elapsedMs] into the pattern. */
    fun litAt(elapsedMs: Long, durationMs: Long = DURATION_MS): Boolean {
        if (elapsedMs < 0) return false
        var at = 0L
        for (step in steps(durationMs)) {
            if (elapsedMs < at + step.ms) return step.lit
            at += step.ms
        }
        return false
    }

    /** Timings for a waveform vibration: a wait, then on and off in turn, the same as the torch. */
    fun vibration(durationMs: Long = DURATION_MS): LongArray = longArrayOf(0L) + steps(durationMs).map { it.ms }
}
