package ro.safetyplease.core.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.safetyplease.core.location.FixGate.Verdict
import ro.safetyplease.core.venue.Venue

class FixGateTest {
    private val lat0 = 44.0
    private val lon0 = 28.0

    /** About 1.1 m of latitude per 0.00001 degree. */
    private fun fix(atMs: Long, northM: Double = 0.0, accuracy: Float = 4f, lon: Double = lon0) =
        LocationFix(lat0 + northM / 111_320.0, lon, accuracy, null, null, FixMethod.GNSS, atMs, atMs, false)

    @Test
    fun acceptsSteadyFixesAndDropsOlderOnes() {
        val gate = FixGate()
        assertEquals(Verdict.ACCEPTED, gate.check(fix(1_000)))
        assertEquals(Verdict.ACCEPTED, gate.check(fix(2_000, northM = 1.0)))
        assertEquals(Verdict.OLD, gate.check(fix(2_000, northM = 1.0)))
        assertEquals(Verdict.OLD, gate.check(fix(1_500)))
    }

    @Test
    fun dropsAccuracyFarAboveTheMedianOnlyOnceThereIsOne() {
        val gate = FixGate()
        assertEquals(Verdict.ACCEPTED, gate.check(fix(1_000, accuracy = 80f)))
        for (i in 2..6) gate.check(fix(i * 1_000L, accuracy = 4f))
        assertEquals(4f, gate.median())
        // 3x the median and 15 m above it
        assertEquals(Verdict.INACCURATE, gate.check(fix(7_000, accuracy = 20f)))
        assertEquals(Verdict.ACCEPTED, gate.check(fix(8_000, accuracy = 18f)))
    }

    @Test
    fun aJumpNobodyCanWalkIsDroppedUntilItRepeats() {
        val gate = FixGate()
        gate.check(fix(0))
        // 100 m in 1 s, far beyond both accuracies
        repeat(FixGate.MAX_REJECTED_JUMPS) { assertEquals(Verdict.TOO_FAST, gate.check(fix(1_000L + it, northM = 100.0))) }
        assertEquals(Verdict.ACCEPTED, gate.check(fix(2_000, northM = 100.0)))
        // walking pace is fine, and both radii count as slack: 20 m in 1 s, less 4 m and 10 m
        assertEquals(Verdict.ACCEPTED, gate.check(fix(3_000, northM = 102.0)))
        assertEquals(Verdict.ACCEPTED, gate.check(fix(4_000, northM = 122.0, accuracy = 10f)))
    }

    @Test
    fun dropsFixesInsideTheVenueWater() {
        val venue = Venue.parse(
            """
            {
              "name": "lac", "bounds": { "minLat": 43.9, "minLon": 27.9, "maxLat": 44.1, "maxLon": 28.1 }, "zones": [],
              "water": [[ {"lat": 44.0, "lon": 28.001}, {"lat": 44.001, "lon": 28.001}, {"lat": 44.001, "lon": 28.002}, {"lat": 44.0, "lon": 28.002} ]]
            }
            """.trimIndent(),
        )
        val gate = FixGate(venue.water)
        assertEquals(Verdict.WATER, gate.check(fix(1_000, northM = 50.0, lon = 28.0015)))
        assertEquals(Verdict.ACCEPTED, gate.check(fix(2_000, northM = 50.0)))
    }

    @Test
    fun venueWithoutWaterHasNone() {
        val venue = Venue.parse("""{ "name": "x", "bounds": { "minLat": 0, "minLon": 0, "maxLat": 1, "maxLon": 1 }, "zones": [] }""")
        assertEquals(emptyList<Any>(), venue.water)
    }

    @Test
    fun ringIsThe95PercentRadius() {
        assertEquals(8f, fix(0, accuracy = 5f).ring95M, 1e-4f)
    }

    @Test
    fun headingFromCourseWhenWalkingElseFromATrustedCompass() {
        assertEquals(90f, Heading.pick(90f, 1.5f, 200f, CompassAccuracy.HIGH))
        assertEquals(200f, Heading.pick(90f, 0.5f, 200f, CompassAccuracy.MEDIUM))
        assertEquals(350f, Heading.pick(null, null, -10f, CompassAccuracy.HIGH))
        assertNull(Heading.pick(90f, 0.5f, 200f, CompassAccuracy.LOW))
        assertNull(Heading.pick(null, 3f, null, CompassAccuracy.UNRELIABLE))
    }
}
