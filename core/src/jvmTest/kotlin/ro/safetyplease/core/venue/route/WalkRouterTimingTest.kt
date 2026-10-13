package ro.safetyplease.core.venue.route

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** Wall time on the JVM only: a simulator runs many times slower, and commonTest checks the work done instead. */
class WalkRouterTimingTest {
    @Test
    fun aRouteOnFiftyFiveThousandCellsTakesUnderFiftyMilliseconds() {
        val router = WalkRouter(Sites.zigzag())
        val (from, to) = Sites.zigzagEnds
        repeat(5) { router.route(from, to) }
        val clock = TimeSource.Monotonic
        val times = (1..10).map {
            val start = clock.markNow()
            assertNotNull(router.route(from, to))
            start.elapsedNow().inWholeMicroseconds
        }
        assertTrue(times.max() < 50_000, "$times µs")
    }
}
