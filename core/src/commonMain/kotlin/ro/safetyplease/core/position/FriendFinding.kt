package ro.safetyplease.core.position

import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.location.LocationFix
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.NextUpdate
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.degrees
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.atan2
import kotlin.math.cos

private const val MINUTE_MS = 60_000L

/** How a friend's position is drawn by its age: a full pin "acum", "acum N min", faded, then grey "ultima poziție cunoscută". */
enum class FriendAge {
    NOW, RECENT, FADED, STALE;

    companion object {
        fun of(ageMs: Long): FriendAge = when {
            ageMs < 2 * MINUTE_MS -> NOW
            ageMs < 10 * MINUTE_MS -> RECENT
            ageMs < 30 * MINUTE_MS -> FADED
            else -> STALE
        }

        fun minutes(ageMs: Long): Int = (maxOf(0L, ageMs) / MINUTE_MS).toInt()
    }
}

/** What the friend's phone said of its next update: a long heartbeat means it stands still, not that it is lost. */
enum class FriendPace {
    MOVING, STATIONARY, ON_REQUEST, UNKNOWN;

    companion object {
        /** Heartbeats of 10 and 15 minutes come only after positions that did not move (`PositionPolicy`). */
        fun of(nextUpdate: Int): FriendPace = when (nextUpdate) {
            in 0..2 -> MOVING
            3, 4 -> STATIONARY
            NextUpdate.ON_REQUEST -> ON_REQUEST
            else -> UNKNOWN
        }
    }
}

object FriendRing {
    /** How far a person may have walked, on average, for each second the position is old. */
    const val DRIFT_MPS = 0.5
    const val MAX_M = 300.0

    /** The 95% radius of a received accuracy class; past 200 m or unknown, the largest ring there is. */
    fun accuracy95(accuracyClass: Int): Double = AccuracyClass.meters(accuracyClass)?.let { it * LocationFix.RING_95.toDouble() } ?: MAX_M

    /**
     * The ring drawn around a friend: the 95% radius grown by [DRIFT_MPS] for each second of age, at most [MAX_M] and
     * at most as far as the site reaches from [at], past which a larger ring adds nothing.
     */
    fun radiusM(accuracy95M: Double, ageMs: Long, at: GeoPoint, site: Bounds?): Double {
        val grown = accuracy95M + DRIFT_MPS * maxOf(0L, ageMs) / 1000.0
        val edge = site?.let { maxOf(farthestCorner(it, at), accuracy95M) } ?: MAX_M
        return minOf(grown, MAX_M, edge)
    }

    private fun farthestCorner(site: Bounds, at: GeoPoint): Double = listOf(
        GeoPoint(site.minLat, site.minLon), GeoPoint(site.minLat, site.maxLon), GeoPoint(site.maxLat, site.minLon), GeoPoint(site.maxLat, site.maxLon),
    ).maxOf { distanceMeters(at, it).toDouble() }
}

sealed interface FriendGuide {
    /** "~120 m, spre nord-est": [sector] 0 north to 7 north-west, [bearingDeg] for the arrow. */
    data class Arrow(val distanceM: Int, val bearingDeg: Double, val sector: Int) : FriendGuide

    /** By a stage the compass can't be trusted: "mergi spre scena X" when the friend is in one, else "reperul N". */
    data class Toward(val distanceM: Int, val stage: String?, val landmark: Int?) : FriendGuide

    /** The two circles overlap or nearly: "e în apropiere, uită-te în jur". */
    data object Near : FriendGuide
}

object Guide {
    const val STAGE = "stage"

    /** An arrow only when the distance is more than twice the two 95% radii together; closer than that, it could point anywhere. */
    fun of(me: GeoPoint, my95M: Double, friend: GeoPoint, friend95M: Double, venue: Venue?): FriendGuide {
        val distance = distanceMeters(me, friend)
        if (distance <= 2 * (my95M + friend95M)) return FriendGuide.Near
        val rounded = round(distance)
        if (venue != null && venue.zoneAt(me.lat, me.lon)?.type == STAGE) {
            val stage = venue.zoneAt(friend.lat, friend.lon)?.takeIf { it.type == STAGE }?.name
            val landmark = venue.nearestLandmark(friend)?.number
            if (stage != null || landmark != null) return FriendGuide.Toward(rounded, stage, landmark)
        }
        return FriendGuide.Arrow(rounded, bearing(me, friend), compassSector(me, friend))
    }

    /** To 10 m under a kilometre, to 50 m beyond: the positions are not better than that. */
    fun round(meters: Int): Int = if (meters < 1000) (meters + 5) / 10 * 10 else (meters + 25) / 50 * 50

    fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val dLat = to.lat - from.lat
        val dLon = (to.lon - from.lon) * cos(radians((from.lat + to.lat) / 2))
        return (degrees(atan2(dLon, dLat)) + 360) % 360
    }

    /** The arrow's angle on screen, clockwise: from the way the phone faces, or from north when that is unknown. */
    fun arrowDeg(bearingDeg: Double, headingDeg: Float?): Float = (((bearingDeg - (headingDeg ?: 0f)) % 360 + 360) % 360).toFloat()
}

/** "Unde ești?" once a minute per friend: the limit the chat already holds, for the button to show. */
object AskLimit {
    fun waitMs(lastAskMs: Long?, nowMs: Long): Long =
        lastAskMs?.let { maxOf(0L, it + ChatManager.REQUEST_INTERVAL_MS - nowMs) } ?: 0L
}
