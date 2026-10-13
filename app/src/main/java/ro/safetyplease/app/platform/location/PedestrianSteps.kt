package ro.safetyplease.app.platform.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ro.safetyplease.core.location.Positioning
import ro.safetyplease.core.location.Step
import kotlin.math.sqrt

/**
 * Steps for dead reckoning across a short GNSS gap. They come from the raw accelerometer, since the step detector
 * needs ACTIVITY_RECOGNITION from Android 10, which the app doesn't ask for; the yaw comes from the game rotation
 * vector, which leaves the magnetometer out (the speakers by a stage throw it off), and [Positioning] turns it into a
 * bearing with the GNSS course. The sensors run only while [Positioning.stepsWanted] says so.
 */
class PedestrianSteps(context: Context) {
    private val sensors: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private val detector = StepDetector()
    private var yawDeg: Double? = null
    private var onStep: ((Step) -> Unit)? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            when (event.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    SensorManager.getOrientation(rotation, orientation)
                    yawDeg = Math.toDegrees(orientation[0].toDouble())
                }
                Sensor.TYPE_ACCELEROMETER -> {
                    val (x, y, z) = event.values
                    val atMs = event.timestamp / 1_000_000
                    if (!detector.onSample(sqrt(x * x + y * y + z * z), atMs)) return
                    val yaw = yawDeg ?: return
                    // the sensor's clock is elapsed realtime; fixes are on the wall clock
                    val wallMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - atMs)
                    onStep?.invoke(Step(wallMs, StepDetector.STEP_M, yaw))
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun follow(positioning: Positioning) {
        scope.launch { positioning.stepsWanted.collect { on -> if (on) start(positioning::onStep) else stop() } }
    }

    private fun start(handler: (Step) -> Unit) {
        val sensors = sensors ?: return
        if (onStep != null) return
        val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val game = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: return
        onStep = handler
        sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensors.registerListener(listener, game, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stop() {
        if (onStep == null) return
        onStep = null
        sensors?.unregisterListener(listener)
        yawDeg = null
        detector.reset()
    }
}

/**
 * A step is a peak of the acceleration's magnitude: smoothed, it rises past [RISE] above gravity, at least [MIN_GAP_MS]
 * after the last step, and must fall below [FALL] before the next one counts.
 */
internal class StepDetector {
    private var smooth = SensorManager.GRAVITY_EARTH
    private var armed = true
    private var lastStepMs = Long.MIN_VALUE / 2

    /** True when the sample at [atMs] completes a step. */
    fun onSample(magnitude: Float, atMs: Long): Boolean {
        smooth += SMOOTHING * (magnitude - smooth)
        val above = smooth - SensorManager.GRAVITY_EARTH
        if (!armed) {
            if (above < FALL) armed = true
            return false
        }
        if (above < RISE || atMs - lastStepMs < MIN_GAP_MS) return false
        armed = false
        lastStepMs = atMs
        return true
    }

    fun reset() {
        smooth = SensorManager.GRAVITY_EARTH
        armed = true
        lastStepMs = Long.MIN_VALUE / 2
    }

    companion object {
        /** An average adult stride; the field campaign measures how far it drifts at 10 and 30 s. */
        const val STEP_M = 0.7
        private const val SMOOTHING = 0.25f
        private const val RISE = 1.2f
        private const val FALL = 0.2f
        private const val MIN_GAP_MS = 300L
    }
}
