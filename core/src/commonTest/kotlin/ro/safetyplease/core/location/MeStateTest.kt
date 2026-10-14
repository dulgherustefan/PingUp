package ro.safetyplease.core.location

import ro.safetyplease.core.position.FriendRing
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What the map says of "me": the same answer on both phones for the same position, time and switches. */
class MeStateTest {
    private val now = 1_760_000_000_000L
    private val here = GeoPoint(44.0, 28.0)
    private val sea = listOf(listOf(GeoPoint(43.99, 27.99), GeoPoint(43.99, 28.01), GeoPoint(44.01, 28.01), GeoPoint(44.01, 27.99)))

    private fun gnss(ageMs: Long, accuracy: Float = 5f) = Fused(GeoFix(here.lat, here.lon, accuracy, now - ageMs), PositionMethod.GNSS)

    @Test
    fun withoutLocationThereIsNothingToShowButAPickedLandmark() {
        assertEquals(MeState.Off, MeState.of(gnss(1_000), now, locating = false))
        assertEquals(MeState.Off, MeState.of(null, now, locating = false))
        val landmark = Fused(GeoFix(here.lat, here.lon, LandmarkFix.RADIUS_M, now), PositionMethod.MANUAL_PIN)
        assertIs<MeState.Fixed>(MeState.of(landmark, now, locating = false), "the person said where they stand")
    }

    @Test
    fun noPositionYetIsTheSearch() {
        assertEquals(MeState.Searching, MeState.of(null, now, locating = true))
    }

    @Test
    fun aFreshFixIsShownWithItsOwnRing() {
        val state = MeState.of(gnss(GeoFix.FRESH_MS - 1), now, locating = true)
        assertIs<MeState.Fixed>(state)
        assertEquals(8.0, state.ringM, 1e-4)
        assertFalse(state.inWater)
    }

    @Test
    fun anOldFixIsShownGreyWithItsAgeAndAGrowingRing() {
        val state = MeState.of(gnss(10 * 60_000L), now, locating = true)
        assertIs<MeState.Stale>(state)
        assertEquals(10 * 60_000L, state.ageMs)
        // 8 m, plus half a metre for each of 600 seconds
        assertEquals(308.0, state.ringM, 1e-4)
        val hours = MeState.of(gnss(6 * 3_600_000L), now, locating = true) as MeState.Stale
        assertEquals(FriendRing.MAX_M, hours.ringM)
    }

    @Test
    fun aFixFromTheFutureHasNoAgeToShow() {
        // the wall clock went back an hour: the fix's time can't be compared with now
        assertEquals(MeState.Searching, MeState.of(gnss(-3_600_000L), now, locating = true))
        assertIs<MeState.Fixed>(MeState.of(gnss(-5_000L), now, locating = true), "a few seconds ahead is the clocks disagreeing")
    }

    @Test
    fun inTheWaterIsSaidNotHidden() {
        val state = MeState.of(gnss(1_000), now, locating = true, water = sea)
        assertIs<MeState.Fixed>(state)
        assertTrue(state.inWater)
    }

    @Test
    fun anUnknownAccuracyGetsAWideRingMarkedUnknown() {
        val state = MeState.of(gnss(1_000, accuracy = Accuracy.UNKNOWN), now, locating = true) as MeState.Fixed
        assertFalse(state.fused.accuracyKnown)
        assertEquals(Accuracy.UNKNOWN_RING_M.toDouble(), state.ringM)
    }
}
