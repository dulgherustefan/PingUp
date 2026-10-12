package ro.safetyplease.app.platform.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ro.safetyplease.core.mesh.BatteryDrain
import ro.safetyplease.core.mesh.PowerProfile
import ro.safetyplease.core.util.Clock

/**
 * The battery as the power policy and the screens see it. [profile] is the one in force at this level and
 * [minutesLeft] how long the battery lasts at the rate measured under it, null until there is a rate.
 */
data class BatteryState(val percent: Int, val charging: Boolean, val temperatureC: Int?, val profile: PowerProfile, val minutesLeft: Int?)

/** Follows the system's battery broadcasts for as long as the process lives. */
class BatteryMonitor(context: Context, private val clock: Clock, private val profileFor: (percent: Int, charging: Boolean) -> PowerProfile) {
    private val drain = BatteryDrain()
    private val current = MutableStateFlow(BatteryState(percent = 100, charging = false, temperatureC = null, profile = profileFor(100, false), minutesLeft = null))
    val state: StateFlow<BatteryState> = current.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update(intent)
    }

    init {
        // the broadcast is sticky: registering returns the current state at once
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)?.let(::update)
    }

    private fun update(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else current.value.percent
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        // tenths of a degree Celsius
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val profile = profileFor(percent, charging)
        drain.record(clock.monoMs(), percent, charging, profile)
        current.value = BatteryState(percent, charging, temperature?.let { it / 10 }, profile, drain.minutesLeft(percent))
    }
}
