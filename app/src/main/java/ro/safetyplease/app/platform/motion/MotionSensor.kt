package ro.safetyplease.app.platform.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import ro.safetyplease.core.incidents.MotionSample

/**
 * The accelerometer for staff man-down, while the session asks for it. Readings come about 25 times a second and
 * may be delivered in batches of a few seconds, which keeps the processor asleep in between; their time is the
 * sensor's, on the same clock as SystemClock.elapsedRealtime().
 */
class MotionSensor(context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listener: SensorEventListener? = null

    val available: Boolean get() = sensor != null

    fun watch(onSample: ((MotionSample) -> Unit)?) {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
        if (onSample == null || sensor == null) return
        val next = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                onSample(MotionSample(event.timestamp / 1_000_000, event.values[0], event.values[1], event.values[2]))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        manager?.registerListener(next, sensor, SAMPLE_US, BATCH_US)
        listener = next
    }

    private companion object {
        const val SAMPLE_US = 40_000
        const val BATCH_US = 5_000_000
    }
}
