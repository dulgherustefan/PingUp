package ro.safetyplease.app.platform.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import ro.safetyplease.core.mesh.BatteryReading

/** Follows the system's battery broadcasts for as long as the process lives and hands on each reading. */
class BatteryMonitor(context: Context, private val onReading: (BatteryReading) -> Unit) {
    private var percent = 100

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
        if (level >= 0 && scale > 0) percent = level * 100 / scale
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        // tenths of a degree Celsius
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        onReading(BatteryReading(percent, charging, temperature?.let { it / 10 }))
    }
}
