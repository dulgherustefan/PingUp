package ro.safetyplease.core.location

import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The display filter on synthetic tracks: less jitter, the same walk, never off the raw fix's circle. */
class DisplayFilterTest {
    private val origin = GeoPoint(44.2, 28.6)

    private fun at(east: Double, north: Double) = GeoPoint(
        origin.lat + north / 111_320.0,
        origin.lon + east / (111_320.0 * cos(radians(origin.lat))),
    )

    private class Noise(seed: Int) {
        private val random = Random(seed)

        fun gauss(sigma: Double): Double = sqrt(-2 * ln(1 - random.nextDouble())) * cos(2 * PI * random.nextDouble()) * sigma
    }

    /** One fix a second along [truth], each off by Gaussian noise of [sigma] metres per axis, reporting [accuracy]. */
    private fun track(truth: List<Pair<Double, Double>>, sigma: Double, accuracy: Float, seed: Int = 1): List<Pair<GeoPoint, GeoFix>> {
        val noise = Noise(seed)
        return truth.mapIndexed { i, (e, n) ->
            val raw = at(e + noise.gauss(sigma), n + noise.gauss(sigma))
            at(e, n) to GeoFix(raw.lat, raw.lon, accuracy, 1_000_000L + i * 1_000L, null)
        }
    }

    private fun pathLength(points: List<GeoPoint>): Double = points.zipWithNext { a, b -> distanceMeters(a, b).toDouble() }.sum()

    private fun rms(errors: List<Double>) = sqrt(errors.sumOf { it * it } / errors.size)

    private fun GeoFix.point() = GeoPoint(lat, lon)

    @Test
    fun standingStillTheDotWandersFarLess() {
        val fixes = track(List(300) { 0.0 to 0.0 }, sigma = 4.0, accuracy = 6f)
        val filter = DisplayFilter()
        val shown = fixes.map { filter.onFix(it.second) }
        val raw = pathLength(fixes.map { it.second.point() })
        val smooth = pathLength(shown.map { it.point() })
        assertTrue(smooth < raw / 3, "jitter: raw $raw m, shown $smooth m")
        val err = rms(shown.drop(20).map { distanceMeters(it.point(), origin).toDouble() })
        val rawErr = rms(fixes.drop(20).map { distanceMeters(it.second.point(), origin).toDouble() })
        assertTrue(err < rawErr, "closer to where the person stands: $err against $rawErr")
    }

    @Test
    fun walkingTheShownTrackKeepsTheWholeDisplacement() {
        // 1.4 m/s north-east for two minutes
        val truth = List(121) { i -> i * 1.4 * cos(PI / 4) to i * 1.4 * sin(PI / 4) }
        val fixes = track(truth, sigma = 4.0, accuracy = 6f, seed = 2)
        val filter = DisplayFilter()
        val shown = fixes.map { filter.onFix(it.second) }
        val walked = distanceMeters(shown.first().point(), shown.last().point())
        assertEquals(168.0, walked.toDouble(), 10.0, "displacement")
        val err = rms(shown.zip(fixes).drop(10).map { (s, f) -> distanceMeters(s.point(), f.first).toDouble() })
        val rawErr = rms(fixes.drop(10).map { distanceMeters(it.second.point(), it.first).toDouble() })
        assertTrue(err < rawErr, "nearer the true track: $err against $rawErr")
        assertTrue(pathLength(shown.map { it.point() }) < pathLength(fixes.map { it.second.point() }), "less zigzag")
    }

    @Test
    fun theShownPointNeverLeavesTheFixCircleSoAStopOrAJumpShowsAtOnce() {
        // a walk, a stop, then a real jump of 60 m (a shuttle, a different gate)
        val truth = List(40) { i -> 0.0 to i * 1.5 } + List(40) { 0.0 to 60.0 } + List(20) { 60.0 to 60.0 }
        val fixes = track(truth, sigma = 3.0, accuracy = 5f, seed = 3)
        val filter = DisplayFilter()
        for ((_, fix) in fixes) {
            val shown = filter.onFix(fix)
            assertTrue(distanceMeters(shown.point(), fix.point()) <= 5 + 1, "within the accuracy of the fix")
            assertEquals(fix.accuracyM, shown.accuracyM, "the radius is the fix's own: nothing claims more precision")
        }
    }

    @Test
    fun aLongGapStartsOverFromTheNextFix() {
        val filter = DisplayFilter()
        filter.onFix(GeoFix(origin.lat, origin.lon, 5f, 0L))
        val far = at(300.0, 0.0)
        val next = filter.onFix(GeoFix(far.lat, far.lon, 5f, DisplayFilter.MAX_GAP_MS + 1))
        assertEquals(far, next.point())
    }

    @Test
    fun stepsMoveTheStateFromTheLastFix() {
        val filter = DisplayFilter()
        assertEquals(null, filter.onStep(0.7, 0.0, 0L, 5f), "nothing to count from yet")
        filter.onFix(GeoFix(origin.lat, origin.lon, 5f, 0L))
        var shown: GeoFix? = null
        repeat(10) { shown = filter.onStep(0.0, 0.7, 1_000L + it * 500L, 6f) }
        assertEquals(7.0, distanceMeters(origin, shown!!.point()).toDouble(), 1.0)
        assertEquals(6f, shown!!.accuracyM)
    }
}
