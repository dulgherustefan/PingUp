package ro.safetyplease.core.location

import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Steadies the point drawn for "me": a constant-velocity Kalman filter in local metres, one axis at a time (the
 * noise is the same in every direction, so the axes don't mix). It only smooths the jitter between fixes: the shown
 * point never leaves the raw fix's accuracy circle, so a real move shows up at once, and the accuracy it reports is
 * the raw one, since a bias common to every fix (multipath by a stage) passes through any filter.
 *
 * Steps ([onStep]) move the state between fixes, for the dead reckoning that bridges a short GNSS gap.
 */
class DisplayFilter(private val accelerationNoise: Double = ACCELERATION_NOISE) {
    private class Axis(var x: Double, var v: Double, var pxx: Double, var pxv: Double, var pvv: Double) {
        fun predict(dt: Double, q: Double) {
            x += v * dt
            pxx += 2 * dt * pxv + dt * dt * pvv + q * dt * dt * dt / 3
            pxv += dt * pvv + q * dt * dt / 2
            pvv += q * dt
        }

        fun update(z: Double, r: Double) {
            val s = pxx + r
            val kx = pxx / s
            val kv = pxv / s
            val y = z - x
            x += kx * y
            v += kv * y
            pvv -= kv * pxv
            pxv *= 1 - kx
            pxx *= 1 - kx
        }
    }

    private var lat0 = 0.0
    private var lon0 = 0.0
    private var east: Axis? = null
    private var north: Axis? = null
    private var lastMs = 0L
    private var last: GeoFix? = null

    /** The point to show for [fix]: the filtered one, pulled back onto the accuracy circle if it fell outside. */
    fun onFix(fix: GeoFix): GeoFix {
        val e = east
        val n = north
        val dt = (fix.timeMs - lastMs) / 1000.0
        val r = variance(fix.accuracyM)
        if (e == null || n == null || dt > MAX_GAP_MS / 1000.0 || dt < 0) {
            lat0 = fix.lat
            lon0 = fix.lon
            east = Axis(0.0, 0.0, r, 0.0, START_SPEED_VARIANCE)
            north = Axis(0.0, 0.0, r, 0.0, START_SPEED_VARIANCE)
            lastMs = fix.timeMs
            return fix.also { last = it }
        }
        if (dt > 0) {
            e.predict(dt, accelerationNoise)
            n.predict(dt, accelerationNoise)
        }
        val (ze, zn) = local(fix.lat, fix.lon)
        e.update(ze, r)
        n.update(zn, r)
        lastMs = fix.timeMs
        // the filter may lag a sudden turn or stop: never show the point farther from the fix than the fix's own radius
        val limit = if (fix.accuracyM > 0f) fix.accuracyM.toDouble() else UNKNOWN_ACCURACY_M
        val off = hypot(e.x - ze, n.x - zn)
        if (off > limit) {
            e.x = ze + (e.x - ze) * limit / off
            n.x = zn + (n.x - zn) * limit / off
        }
        return shown(fix.accuracyM, fix.timeMs, fix.courseDeg).also { last = it }
    }

    /**
     * A step of [eastM] and [northM] metres at [timeMs]; [accuracyM] is what the point is worth after it. Null before
     * the first fix, which the steps are counted from.
     */
    fun onStep(eastM: Double, northM: Double, timeMs: Long, accuracyM: Float): GeoFix? {
        val e = east ?: return null
        val n = north ?: return null
        e.x += eastM
        n.x += northM
        e.pxx += STEP_VARIANCE
        n.pxx += STEP_VARIANCE
        lastMs = maxOf(lastMs, timeMs)
        return shown(accuracyM, timeMs, last?.courseDeg).also { last = it }
    }

    private fun shown(accuracyM: Float, timeMs: Long, course: Float?): GeoFix {
        val (lat, lon) = geo(east!!.x, north!!.x)
        return GeoFix(lat, lon, accuracyM, timeMs, course)
    }

    private fun local(lat: Double, lon: Double): Pair<Double, Double> =
        (lon - lon0) * METERS_PER_DEGREE * cos(radians(lat0)) to (lat - lat0) * METERS_PER_DEGREE

    private fun geo(eastM: Double, northM: Double): Pair<Double, Double> =
        lat0 + northM / METERS_PER_DEGREE to lon0 + eastM / (METERS_PER_DEGREE * cos(radians(lat0)))

    private fun variance(accuracyM: Float): Double {
        val sigma = if (accuracyM > 0f) accuracyM.toDouble() else UNKNOWN_ACCURACY_M
        return sigma * sigma
    }

    companion object {
        /** White acceleration, m²/s³: a walker who speeds up, stops and turns in a crowd. */
        const val ACCELERATION_NOISE = 0.5

        /** Past this without a fix the filter starts over from the next one. */
        const val MAX_GAP_MS = 60_000L
        const val UNKNOWN_ACCURACY_M = 10.0
        private const val START_SPEED_VARIANCE = 4.0
        private const val STEP_VARIANCE = 0.25
        private const val METERS_PER_DEGREE = 111_320.0
    }
}
