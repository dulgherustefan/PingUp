package ro.safetyplease.core.location

import ro.safetyplease.core.position.FriendRing
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.pointInPolygon

/**
 * What the map says of "me", the same on both phones: a fresh position, the last one with its age, no position while
 * the receiver searches, or location off. [of] is pure: the screen gives what it knows and the time.
 */
sealed interface MeState {
    /** Location is off or not allowed: no fix will come. */
    data object Off : MeState

    /** Location runs and there is no position yet, or none whose time can be trusted. */
    data object Searching : MeState

    /**
     * A position to draw, with [ringM] around it (dashed when [Fused.accuracyKnown] is false). [inWater]: inside the
     * venue's water, kept since someone may really be there, and said, since it may be a GNSS error.
     */
    sealed interface Placed : MeState {
        val fused: Fused
        val ringM: Double
        val inWater: Boolean
    }

    /** Under [GeoFix.FRESH_MS] old, or the landmark the person said they stand by. */
    data class Fixed(override val fused: Fused, override val inWater: Boolean) : Placed {
        override val ringM: Double get() = fused.ringM.toDouble()
    }

    /**
     * The last position, [ageMs] old, drawn like a stale friend: grey, its ring grown by [FriendRing.DRIFT_MPS] for each
     * second of age. The receiver keeps searching meanwhile.
     */
    data class Stale(override val fused: Fused, val ageMs: Long, override val ringM: Double, override val inWater: Boolean) : Placed

    companion object {
        /**
         * [fused] is the position the session shows ([Positioning.fused]); [locating] says location is allowed and
         * switched on; [water] is the venue's.
         */
        fun of(fused: Fused?, nowMs: Long, locating: Boolean, water: List<List<GeoPoint>> = emptyList()): MeState {
            // a landmark the person picked holds without GNSS
            val manual = fused?.method == PositionMethod.MANUAL_PIN
            if (!locating && !manual) return Off
            if (fused == null) return Searching
            val fix = fused.fix
            val ageMs = nowMs - fix.timeMs
            // stamped well ahead of now: the wall clock went back, and how old the fix is can't be told
            if (ageMs < -Positioning.CLOCK_STEP_MS) return Searching
            val inWater = water.any { pointInPolygon(fix.lat, fix.lon, it) }
            if (ageMs < GeoFix.FRESH_MS) return Fixed(fused, inWater)
            return Stale(fused, ageMs, FriendRing.radiusM(fused.ringM.toDouble(), ageMs, GeoPoint(fix.lat, fix.lon), null), inWater)
        }
    }
}
