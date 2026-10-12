package ro.safetyplease.core.mesh

import ro.safetyplease.core.util.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatteryWatchTest {
    private class ManualClock(var now: Long = 0) : Clock {
        override fun wallMs() = now
        override fun monoMs() = now
    }

    private val clock = ManualClock()
    private val watch = BatteryWatch(clock) { percent, charging -> PowerPolicy.profile(false, false, percent, charging) }

    @Test
    fun startsFullUntilTheFirstReading() {
        assertEquals(BatteryState(100, false, null, PowerProfile.LEAF, null), watch.state.value)
    }

    @Test
    fun eachReadingGivesTheProfileAndHowLongTheBatteryLasts() {
        watch.update(BatteryReading(60, charging = false, temperatureC = 31))
        assertEquals(BatteryState(60, false, 31, PowerProfile.LEAF, null), watch.state.value)
        // a percent every 6 minutes: 10 an hour, so 55 % lasts 5.5 hours
        for (percent in 59 downTo 55) {
            clock.now += 6 * 60_000
            watch.update(BatteryReading(percent, charging = false, temperatureC = 31))
        }
        assertEquals(PowerProfile.LEAF, watch.state.value.profile)
        assertEquals(330, watch.state.value.minutesLeft)
    }

    @Test
    fun aChargerStartsTheMeasurementAgain() {
        watch.update(BatteryReading(60, false, null))
        for (percent in 59 downTo 55) {
            clock.now += 6 * 60_000
            watch.update(BatteryReading(percent, false, null))
        }
        watch.update(BatteryReading(55, charging = true, temperatureC = null))
        assertNull(watch.state.value.minutesLeft)
        watch.update(BatteryReading(10, charging = false, temperatureC = null))
        assertEquals(PowerProfile.ECONOMY, watch.state.value.profile)
    }
}
