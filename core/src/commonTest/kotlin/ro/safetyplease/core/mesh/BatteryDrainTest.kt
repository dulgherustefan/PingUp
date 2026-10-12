package ro.safetyplease.core.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertNull(drain.percentPerHour(), "the first level has no exact start")
        drain.fall(79, 78, every = 6, start = 6 * min)
        assertNull(drain.percentPerHour(), "one percent is not a rate")
        val quick = BatteryDrain()
        quick.fall(80, 76, every = 2)
        assertNull(quick.percentPerHour(), "six minutes is a burst, not a rate")
    }

    @Test
    fun theRateIsTheDropBetweenTheFirstAndLastDropOverTheTimeBetweenThem() {
        val drain = BatteryDrain()
        // 6 minutes a percent: 10 per hour; the first reading only starts the count
        drain.fall(80, 70, every = 6)
        assertEquals(10.0, drain.percentPerHour()!!, 1e-9)
        assertEquals(420, drain.minutesLeft(70), "70% at 10 per hour is 7 hours")
        assertEquals(55, drain.minutesLeft(9))
    }

    @Test
    fun minutesRoundToTheNearestFiveAndNeverReachZero() {
        val drain = BatteryDrain()
        drain.fall(90, 80, every = 6)
        assertEquals(10.0, drain.percentPerHour()!!, 1e-9, "10 per hour")
        assertEquals(5, drain.minutesLeft(0))
        assertEquals(5, drain.minutesLeft(1), "6 minutes")
        assertEquals(10, drain.minutesLeft(2), "12 minutes")
        assertEquals(55, drain.minutesLeft(9), "54 minutes")
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
        assertNull(drain.percentPerHour(), "economy runs at its own rate")
        drain.fall(68, 60, every = 12, start = 82 * min, profile = PowerProfile.ECONOMY)
        assertEquals(5.0, drain.percentPerHour()!!, 1e-9)
        drain.record(200 * min, 70, charging = true, profile = PowerProfile.STAFF)
        assertNull(drain.percentPerHour(), "charging")
        drain.fall(69, 60, every = 12, start = 300 * min, profile = PowerProfile.STAFF)
        assertEquals(5.0, drain.percentPerHour()!!, 1e-9, "a new count after the charger")
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
