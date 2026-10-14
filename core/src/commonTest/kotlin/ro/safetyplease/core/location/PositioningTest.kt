package ro.safetyplease.core.location

import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The landmark correction, its end, and the steps that bridge a short GNSS gap. */
class PositioningTest {
    private class FakeClock(var now: Long = 1_760_000_000_000L) : Clock {
        override fun wallMs() = now
        override fun monoMs() = now
    }

    private val origin = GeoPoint(44.2, 28.6)
    private val landmark = at(0.0, 20.0)

    private fun at(east: Double, north: Double) = GeoPoint(
        origin.lat + north / 111_320.0,
        origin.lon + east / (111_320.0 * cos(radians(origin.lat))),
    )

    private fun fix(clock: FakeClock, point: GeoPoint, accuracy: Float, course: Float? = null) =
        GeoFix(point.lat, point.lon, accuracy, clock.now, course)

    private fun Positioning.shownAt(): GeoPoint = position.value!!.let { GeoPoint(it.lat, it.lon) }

    @Test
    fun theLandmarkStandsForTenMinutesThenTheFixesComeBack() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.onRaw(fix(clock, origin, 40f))
        p.nearLandmark(7, landmark)
        assertEquals(landmark, p.shownAt())
        assertEquals(LandmarkFix.RADIUS_M, p.position.value!!.accuracyM)
        assertEquals(PositionMethod.MANUAL_PIN, p.fused.value!!.method)

        // a poor fix, or a good one close by, doesn't move it; the landmark stays fresh while it holds
        clock.now += 60_000
        p.onRaw(fix(clock, at(0.0, -40.0), 40f))
        clock.now += 60_000
        p.onRaw(fix(clock, at(0.0, 5.0), 8f))
        clock.now += 5 * 60_000L
        p.tick()
        assertEquals(landmark, p.shownAt())
        assertTrue(p.position.value!!.isFresh(clock.now))

        clock.now += 3 * 60_000L
        p.tick()
        assertEquals(PositionMethod.GNSS, p.fused.value!!.method, "ten minutes on, the fixes lead again")
        assertNull(p.landmark.value)
        assertTrue(distanceMeters(p.shownAt(), at(0.0, 5.0)) <= 9)
    }

    @Test
    fun aGoodFixFarFromTheLandmarkEndsItAtOnce() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.nearLandmark(7, landmark)
        clock.now += 30_000
        val elsewhere = at(0.0, 60.0)
        p.onRaw(fix(clock, elsewhere, LandmarkFix.GOOD_FIX_M))
        assertNull(p.landmark.value)
        assertEquals(PositionMethod.GNSS, p.fused.value!!.method)
        assertEquals(elsewhere, p.shownAt())
    }

    @Test
    fun withoutAnyFixTheLandmarkIsThePositionAndEndsWithNothing() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.nearLandmark(3, landmark)
        assertEquals(landmark, p.shownAt())
        p.clearLandmark()
        assertNull(p.position.value)
    }

    @Test
    fun stepsBridgeAGapOfUpToThirtySecondsOnceTheCourseGivesThemANorth() {
        val clock = FakeClock()
        val p = Positioning(clock)
        // walking north: the GNSS course says 0°, the phone's yaw reads 90° at the same time
        p.onStep(Step(clock.now - 500, 0.7, 90.0))
        p.onRaw(fix(clock, origin, 5f, course = 0f))
        val start = p.shownAt()

        // fixes still coming: the steps wait
        p.onStep(Step(clock.now + 1_000, 0.7, 90.0))
        assertEquals(start, p.shownAt())

        // under a tent roof: 20 steps between 3 s and 13 s after the last fix
        repeat(20) { p.onStep(Step(clock.now + 3_000 + it * 500L, 0.7, 90.0)) }
        val bridged = p.shownAt()
        assertEquals(14.0, distanceMeters(start, bridged).toDouble(), 1.5)
        assertTrue(bridged.lat > start.lat, "north, as the course taught")
        assertTrue(p.position.value!!.accuracyM > 5f, "and less sure than the fix")

        // past 30 s the steps stop moving it
        p.onStep(Step(clock.now + Positioning.PDR_GAP_MS + 1_000, 0.7, 90.0))
        assertEquals(bridged, p.shownAt())
    }

    @Test
    fun stepsWithoutACourseToAnchorThemMoveNothing() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.onRaw(fix(clock, origin, 5f))
        repeat(10) { p.onStep(Step(clock.now + 5_000 + it * 500L, 0.7, 90.0)) }
        assertEquals(origin, p.shownAt())
    }

    @Test
    fun stepsAloneLeaveTheLandmarkInPlace() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.nearLandmark(7, landmark)
        repeat(50) { p.onStep(Step(clock.now + it * 500L, 0.7, 0.0)) }
        assertEquals(7, p.landmark.value?.number, "only a good fix or its time ends it")
        assertEquals(landmark, p.shownAt())
    }

    @Test
    fun aClockSetBackDoesNotFreezeThePosition() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.onRaw(fix(clock, origin, 5f))
        p.nearLandmark(7, landmark)
        clock.now -= 60 * 60_000L
        p.tick()
        assertNull(p.landmark.value, "a landmark said in the future has no time left")
        val moved = at(30.0, 0.0)
        p.onRaw(fix(clock, moved, 5f))
        assertEquals(moved, p.shownAt(), "the first fix after the step starts the filter over")
        clock.now += 1_000
        p.onRaw(fix(clock, at(31.0, 0.0), 5f))
        assertTrue(distanceMeters(p.shownAt(), at(31.0, 0.0)) <= 5)

        // a repeat of the last fix, or one a few seconds out of order, changes nothing
        val shown = p.shownAt()
        p.onRaw(fix(FakeClock(clock.now - 3_000), at(80.0, 0.0), 5f))
        assertEquals(shown, p.shownAt())
    }

    @Test
    fun theRingHoldsTheRawFixAroundTheShownPoint() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.onRaw(fix(clock, origin, 5f))
        assertEquals(8f, p.fused.value!!.ringM, 1e-4f, "the first fix is shown where it is")
        // standing still, a fix 6 m off: the shown point stays nearer the old ones, and the ring grows by the difference
        clock.now += 1_000
        val off = at(6.0, 0.0)
        p.onRaw(fix(clock, off, 5f))
        val fused = p.fused.value!!
        val gap = distanceMeters(p.shownAt(), off)
        assertTrue(gap > 0, "the filter smoothed it")
        assertEquals(8f + gap, fused.ringM, 1e-4f)
        assertTrue(distanceMeters(p.shownAt(), off) + 8f <= fused.ringM + 1e-4f, "the fix's own 95% circle is inside the ring")
        assertFalse(fused.pdr)
    }

    @Test
    fun stepsMarkThePositionAndAnUnknownAccuracyStaysUnknown() {
        val clock = FakeClock()
        val p = Positioning(clock)
        p.onStep(Step(clock.now - 500, 0.7, 90.0))
        p.onRaw(GeoFix(origin.lat, origin.lon, Accuracy.UNKNOWN, clock.now, 0f, mock = true))
        assertTrue(p.position.value!!.mock, "a fix another app made up stays marked")
        assertFalse(p.fused.value!!.accuracyKnown)
        assertEquals(Accuracy.UNKNOWN_RING_M, p.fused.value!!.ringM, "wide, never none")
        repeat(10) { p.onStep(Step(clock.now + 3_000 + it * 500L, 0.7, 90.0)) }
        val stepped = p.fused.value!!
        assertTrue(stepped.pdr, "moved by steps since the fix")
        assertEquals(PositionMethod.GNSS, stepped.method, "the wire has no method for it")
        assertFalse(stepped.accuracyKnown, "steps can't make an unknown accuracy exact")
        assertTrue(stepped.fix.mock)
        p.onRaw(fix(FakeClock(clock.now + 10_000), origin, 5f))
        assertFalse(p.fused.value!!.pdr, "a fix again")
    }

    @Test
    fun stepsAreCountedOnlyWithTheSettingAndSomethingThatNeedsThem() {
        val p = Positioning(FakeClock())
        p.reportOpen(true)
        assertFalse(p.stepsWanted.value, "the setting is off by default")
        p.follow(setting = true, exactShare = false)
        assertTrue(p.stepsWanted.value, "a report is being written")
        p.reportOpen(false)
        assertFalse(p.stepsWanted.value, "nothing needs them now")
        p.follow(setting = true, exactShare = true)
        assertTrue(p.stepsWanted.value)
        p.follow(setting = true, exactShare = false)
        p.navigating(true)
        assertTrue(p.stepsWanted.value)
        // the route screen and an evacuation's way out guide at once; one ending leaves the other its steps
        p.navigating(true)
        p.navigating(false)
        assertTrue(p.stepsWanted.value)
        p.navigating(false)
        assertFalse(p.stepsWanted.value)
        p.navigating(true)
        p.follow(setting = false, exactShare = true)
        assertFalse(p.stepsWanted.value)
    }
}
