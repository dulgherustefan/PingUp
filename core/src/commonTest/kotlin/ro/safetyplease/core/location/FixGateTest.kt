package ro.safetyplease.core.location

import ro.safetyplease.core.location.FixGate.Verdict
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixGateTest {
    private val lat0 = 44.0
    private val lon0 = 28.0

    /** About 1.1 m of latitude per 0.00001 degree. */
    private fun fix(
        atMs: Long,
        northM: Double = 0.0,
        accuracy: Float = 4f,
        lon: Double = lon0,
        speed: Float? = null,
        speedAccuracy: Float? = null,
    ) = LocationFix(lat0 + northM / 111_320.0, lon, accuracy, null, speed, FixMethod.GNSS, atMs, atMs, false, speedAccuracy)

    /** Five 4 m fixes, a second apart, ending at [endMs]: the median is 4 m from then on. */
    private fun FixGate.settled(endMs: Long = 5_000) {
        for (i in 4 downTo 0) assertEquals(Verdict.ACCEPTED, check(fix(endMs - i * 1_000L)))
        assertEquals(4f, median())
    }

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
    fun indoorsTheThirdInaccurateFixInARowGoesThroughAndSoDoTheNext() {
        val gate = FixGate()
        gate.settled()
        assertEquals(Verdict.INACCURATE, gate.check(fix(6_000, accuracy = 30f)))
        assertEquals(Verdict.INACCURATE, gate.check(fix(7_000, accuracy = 30f)))
        assertEquals(Verdict.ACCEPTED, gate.check(fix(8_000, accuracy = 30f)), "a wide ring, not a frozen dot")
        assertEquals(Verdict.ACCEPTED, gate.check(fix(9_000, accuracy = 35f)), "while they keep coming")
        // a good fix ends the run, and the next poor one waits again
        assertEquals(Verdict.ACCEPTED, gate.check(fix(10_000)))
        assertEquals(Verdict.INACCURATE, gate.check(fix(11_000, accuracy = 30f)))
    }

    @Test
    fun inaccurateFixesForTenSecondsGoThroughWhenTheyComeSlowly() {
        val gate = FixGate()
        gate.settled()
        // still, under a roof: one fix every 45 s
        assertEquals(Verdict.INACCURATE, gate.check(fix(6_000, accuracy = 40f)))
        assertEquals(Verdict.INACCURATE, gate.check(fix(6_000 + FixGate.ESCAPE_MS - 1, accuracy = 40f)))
        val gate2 = FixGate()
        gate2.settled()
        assertEquals(Verdict.INACCURATE, gate2.check(fix(6_000, accuracy = 40f)))
        assertEquals(Verdict.ACCEPTED, gate2.check(fix(51_000, accuracy = 40f)))
    }

    @Test
    fun theMedianIsOfTheLastTwoMinutesOnly() {
        val gate = FixGate()
        gate.settled()
        // the 4 m fixes of a while ago say nothing of the sky now
        assertEquals(Verdict.ACCEPTED, gate.check(fix(5_000 + FixGate.WINDOW_MS + 1_000, accuracy = 25f)))
        assertNull(gate.median(), "one fix in the window is not enough for a median")
    }

    @Test
    fun aResetForgetsTheSkyAndThePlaceOfTheLastRun() {
        val gate = FixGate()
        gate.settled()
        gate.reset()
        assertNull(gate.median())
        assertEquals(Verdict.ACCEPTED, gate.check(fix(6_000, northM = 500.0, accuracy = 30f)), "neither too poor nor too far now")
        assertEquals(Verdict.ACCEPTED, gate.check(fix(7_000, northM = 501.0, accuracy = 30f)))
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
    fun aCarWhoseDopplerSpeedAgreesWithTheDistanceIsFollowedAtOnce() {
        val gate = FixGate()
        gate.check(fix(0, speed = 25f, speedAccuracy = 0.5f))
        for (i in 1..5) {
            val verdict = gate.check(fix(i * 2_000L, northM = i * 50.0, speed = 25f, speedAccuracy = 0.5f))
            assertEquals(Verdict.ACCEPTED, verdict, "fix $i at 90 km/h")
        }
    }

    @Test
    fun aMultipathJumpIsStillDroppedWhateverTheSpeedSays() {
        val gate = FixGate()
        gate.check(fix(0, speed = 1.4f, speedAccuracy = 0.3f))
        assertEquals(Verdict.TOO_FAST, gate.check(fix(1_000, northM = 100.0, speed = 1.4f, speedAccuracy = 0.3f)), "walking pace")
        // a speed the receiver itself doubts doesn't vouch for anything
        val doubtful = FixGate()
        doubtful.check(fix(0, speed = 25f))
        assertEquals(Verdict.TOO_FAST, doubtful.check(fix(2_000, northM = 50.0, speed = 25f, speedAccuracy = 8f)))
        // nor does a speed that disagrees with the distance
        val car = FixGate()
        car.check(fix(0, speed = 25f, speedAccuracy = 0.5f))
        assertEquals(Verdict.TOO_FAST, car.check(fix(2_000, northM = 200.0, speed = 25f, speedAccuracy = 0.5f)))
    }

    @Test
    fun aFixInTheVenueWaterIsKeptAndFlagged() {
        val venue = Venue.parse(
            """
            {
              "name": "lac", "bounds": { "minLat": 43.9, "minLon": 27.9, "maxLat": 44.1, "maxLon": 28.1 }, "zones": [],
              "water": [[ {"lat": 44.0, "lon": 28.001}, {"lat": 44.001, "lon": 28.001}, {"lat": 44.001, "lon": 28.002}, {"lat": 44.0, "lon": 28.002} ]]
            }
            """.trimIndent(),
        )
        val gate = FixGate(venue.water)
        val swimming = gate.check(fix(1_000, northM = 50.0, lon = 28.0015))
        assertEquals(Verdict.WATER, swimming)
        assertTrue(swimming.accepted, "someone at the waterline must not vanish")
        assertEquals(Verdict.OLD, gate.check(fix(1_000, northM = 50.0)), "it counts as the last fix")
        assertEquals(Verdict.ACCEPTED, gate.check(fix(60_000, northM = 50.0)))
        assertFalse(Verdict.INACCURATE.accepted || Verdict.TOO_FAST.accepted || Verdict.OLD.accepted)
    }

    @Test
    fun anUnknownAccuracyIsNeitherExactNorJudged() {
        val gate = FixGate()
        gate.settled()
        assertEquals(Verdict.ACCEPTED, gate.check(fix(6_000, accuracy = Accuracy.UNKNOWN)), "nothing to judge it by")
        assertEquals(4f, gate.median(), "and it doesn't count in the median")
        // 25 m in 1 s from a fix of unknown accuracy: its slack is the filter's 10 m guess; taken as 0 m, a jump
        assertEquals(Verdict.ACCEPTED, gate.check(fix(7_000, northM = 25.0)))
        assertEquals(Accuracy.UNKNOWN_RING_M, fix(0, accuracy = Accuracy.UNKNOWN).ring95M, "a wide ring, never none")
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

    @Test
    fun anIphoneCourseAndCompassReadTheSameWay() {
        assertEquals(90f, Heading.course(90f, 10f, 1.5f))
        assertEquals(90f, Heading.course(90f, null, 1.5f), "an error not given doesn't count against it")
        assertNull(Heading.course(90f, 60f, 1.5f), "too wide")
        assertNull(Heading.course(90f, 10f, 0.5f), "standing")
        assertEquals(CompassAccuracy.UNRELIABLE, CompassAccuracy.ofErrorDeg(-1.0))
        assertEquals(CompassAccuracy.HIGH, CompassAccuracy.ofErrorDeg(10.0))
        assertEquals(CompassAccuracy.MEDIUM, CompassAccuracy.ofErrorDeg(25.0))
        assertEquals(CompassAccuracy.LOW, CompassAccuracy.ofErrorDeg(45.0))
        assertEquals(CompassAccuracy.UNRELIABLE, CompassAccuracy.ofErrorDeg(180.0))
        assertEquals(CompassAccuracy.UNRELIABLE, CompassAccuracy.ofErrorDeg(Double.NaN))
    }

    @Test
    fun theIntakeDropsOldFixesAKeptFirstOneAndMoreThanOneASecond() {
        val intake = FixIntake()
        assertFalse(intake.take(ageMs = 30_000, elapsedMs = 100_000), "kept from before the start")
        assertTrue(intake.take(ageMs = 2_000, elapsedMs = 100_500))
        assertFalse(intake.take(ageMs = 0, elapsedMs = 101_000), "half a second later")
        assertTrue(intake.take(ageMs = 0, elapsedMs = 101_480), "a second later, stamped a little early")
        assertTrue(intake.take(ageMs = 30_000, elapsedMs = 105_000), "once running, a late batch still counts")
        assertFalse(intake.take(ageMs = GeoFix.FRESH_MS + 1, elapsedMs = 110_000), "too old to say where the person is")
        intake.restart()
        assertFalse(intake.take(ageMs = 20_000, elapsedMs = 120_000), "a new start asks for a fresh first fix again")
    }
}
