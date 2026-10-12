package ro.safetyplease.app.platform.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import androidx.core.content.ContextCompat
import ro.safetyplease.core.location.CompassAccuracy

/**
 * The sensors that tell the duty cycle whether the person walks, and the compass for the heading. The step detector
 * needs ACTIVITY_RECOGNITION from Android 10, which the app does not ask for, so there it is used only if granted;
 * significant motion and the motion/stationary detectors need no permission.
 */
internal class Motion(context: Context) {
    class Available(val steps: Boolean, val canWake: Boolean)

    private val appContext = context.applicationContext
    private val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private var onMotion: () -> Unit = {}
    private var onStill: () -> Unit = {}
    private var significant: Sensor? = null
    private var compassOn = false

    /** Degrees from north, from the rotation vector; null before the first reading. */
    var azimuth: Float? = null
        private set
    var compassAccuracy = CompassAccuracy.UNRELIABLE
        private set

    private val events = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_STATIONARY_DETECT -> onStill()
                else -> onMotion()
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            onMotion()
            // a trigger sensor disarms itself after firing
            significant?.let { sensors?.requestTriggerSensor(this, it) }
        }
    }

    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private val compass = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
            compassAccuracy = when (accuracy) {
                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassAccuracy.HIGH
                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassAccuracy.MEDIUM
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassAccuracy.LOW
                else -> CompassAccuracy.UNRELIABLE
            }
        }
    }

    fun start(onMotion: () -> Unit, onStill: () -> Unit): Available {
        this.onMotion = onMotion
        this.onStill = onStill
        val sensors = sensors ?: return Available(steps = false, canWake = false)
        fun listen(type: Int) = sensors.getDefaultSensor(type)?.let { sensors.registerListener(events, it, SensorManager.SENSOR_DELAY_NORMAL) } == true
        val stepsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        val steps = stepsAllowed && listen(Sensor.TYPE_STEP_DETECTOR)
        val moved = listen(Sensor.TYPE_MOTION_DETECT)
        listen(Sensor.TYPE_STATIONARY_DETECT)
        significant = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        val woken = significant?.let { sensors.requestTriggerSensor(trigger, it) } == true
        return Available(steps, steps || moved || woken)
    }

    fun stop() {
        val sensors = sensors ?: return
        sensors.unregisterListener(events)
        significant?.let { sensors.cancelTriggerSensor(trigger, it) }
        significant = null
        compass(false)
    }

    /** Only while a screen shows the position: the rotation vector keeps the gyroscope awake. */
    fun compass(on: Boolean) {
        if (on == compassOn) return
        val sensors = sensors ?: return
        compassOn = on
        if (on) {
            sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(compass, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } else {
            sensors.unregisterListener(compass)
            azimuth = null
            compassAccuracy = CompassAccuracy.UNRELIABLE
        }
    }
}
