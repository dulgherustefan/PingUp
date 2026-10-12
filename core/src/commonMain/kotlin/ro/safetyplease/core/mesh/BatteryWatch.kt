package ro.safetyplease.core.mesh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ro.safetyplease.core.util.Clock

/** One reading of the battery, as the system gives it; [temperatureC] where the phone tells it. */
data class BatteryReading(val percent: Int, val charging: Boolean, val temperatureC: Int?)

/**
 * The battery as the app shows it and plans by. [profile] is the power profile in force at this level, [minutesLeft]
 * how long the battery lasts at the pace measured under that profile, null until there is a pace.
 */
data class BatteryState(val percent: Int, val charging: Boolean, val temperatureC: Int?, val profile: PowerProfile, val minutesLeft: Int?)

/** Follows the system's readings of the battery; [profileFor] is the power profile a level puts the phone in. */
class BatteryWatch(private val clock: Clock, private val profileFor: (percent: Int, charging: Boolean) -> PowerProfile) {
    private val drain = BatteryDrain()
    private val current = MutableStateFlow(BatteryState(100, false, null, profileFor(100, false), null))
    val state: StateFlow<BatteryState> = current.asStateFlow()

    fun update(reading: BatteryReading) {
        val profile = profileFor(reading.percent, reading.charging)
        drain.record(clock.monoMs(), reading.percent, reading.charging, profile)
        current.value = BatteryState(reading.percent, reading.charging, reading.temperatureC, profile, drain.minutesLeft(reading.percent))
    }
}
