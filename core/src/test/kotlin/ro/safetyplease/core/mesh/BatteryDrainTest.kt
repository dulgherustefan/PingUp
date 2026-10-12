package ro.safetyplease.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryDrainTest {
    private val min = 60_000L

    /** A phone losing one percent every [every] minutes from [from] down to [to], seen on the minute it drops. */
    private fun BatteryDrain.fall(from: Int, to: Int, every: Long, start: Long = 0, profile: PowerProfile = PowerProfile.STAFF) {
        var at = start
        for (level in from downTo to) {
            record(at, level, charging = false, profile = profile)
            at += every * min
        }
    }

    @Test
    fun nothingIsSaidBeforeTheMeasurementIsLongEnough() {
        val drain = BatteryDrain()
        assertNull(drain.percentPerHour())
        assertNull(drain.minutesLeft(80))
        drain.fall(80, 79, every = 6)
        assertNull("the first level has no exact start", drain.percentPerHour())
        drain.fall(79, 78, every = 6, start = 6 * min)
        assertNull("one percent is not a rate", drain.percentPerHour())
        val quick = BatteryDrain()
        quick.fall(80, 76, every = 2)
        assertNull("six minutes is a burst, not a rate", quick.percentPerHour())
    }

    @Test
    fun theRateIsTheDropBetweenTheFirstAndLastDropOverTheTimeBetweenThem() {
        val drain = BatteryDrain()
        // 6 minutes a percent: 10 per hour; the first reading only starts the count
        drain.fall(80, 70, every = 6)
        assertEquals(10.0, drain.percentPerHour()!!, 1e-9)
        assertEquals("70% at 10 per hour is 7 hours", 420, drain.minutesLeft(70))
        assertEquals(55, drain.minutesLeft(9))
    }

    @Test
    fun minutesRoundToTheNearestFiveAndNeverReachZero() {
        val drain = BatteryDrain()
        drain.fall(90, 80, every = 6)
        assertEquals("10 per hour", 10.0, drain.percentPerHour()!!, 1e-9)
        assertEquals(5, drain.minutesLeft(0))
        assertEquals("6 minutes", 5, drain.minutesLeft(1))
        assertEquals("12 minutes", 10, drain.minutesLeft(2))
        assertEquals("54 minutes", 55, drain.minutesLeft(9))
    }

    @Test
    fun onlyTheLastHourCounts() {
        val drain = BatteryDrain()
        drain.fall(100, 90, every = 3, start = 0)
        // two hours later the phone burns slower: 2 per hour
        drain.fall(89, 80, every = 30, start = 120 * min)
        assertEquals(2.0, drain.percentPerHour()!!, 1e-9)
    }

    @Test
    fun aChangeOfProfileOrAChargerStartsOver() {
        val drain = BatteryDrain()
        drain.fall(80, 70, every = 6, profile = PowerProfile.STAFF)
        assertNotNull(drain.percentPerHour())
        drain.record(70 * min, 69, charging = false, profile = PowerProfile.ECONOMY)
        assertNull("economy runs at its own rate", drain.percentPerHour())
        drain.fall(68, 60, every = 12, start = 82 * min, profile = PowerProfile.ECONOMY)
        assertEquals(5.0, drain.percentPerHour()!!, 1e-9)
        drain.record(200 * min, 70, charging = true, profile = PowerProfile.STAFF)
        assertNull("charging", drain.percentPerHour())
        drain.fall(69, 60, every = 12, start = 300 * min, profile = PowerProfile.STAFF)
        assertEquals("a new count after the charger", 5.0, drain.percentPerHour()!!, 1e-9)
    }

    @Test
    fun aLevelThatGoesUpWithoutAChargerStartsOver() {
        val drain = BatteryDrain()
        drain.fall(80, 70, every = 6)
        drain.record(70 * min, 72, charging = false, profile = PowerProfile.STAFF)
        assertNull(drain.percentPerHour())
    }

    @Test
    fun aBatteryThatHoldsItsLevelHasNoRateOfFall() {
        val drain = BatteryDrain()
        for (i in 0..10) drain.record(i * 10 * min, 80, charging = false, profile = PowerProfile.ANCHOR)
        assertNull(drain.percentPerHour())
        assertNull(drain.minutesLeft(80))
    }
}
