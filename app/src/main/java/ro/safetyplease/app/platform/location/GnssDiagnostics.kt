package ro.safetyplease.app.platform.location

import android.annotation.SuppressLint
import android.location.LocationManager
import android.os.Build
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.safetyplease.core.location.GnssCsv
import ro.safetyplease.core.location.GnssStats
import ro.safetyplease.core.location.GnssSummary
import ro.safetyplease.core.location.L5
import ro.safetyplease.core.location.LocationFix
import ro.safetyplease.core.location.SatSignal
import java.io.File
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * What the GNSS chip does while the location source runs: satellites in the fix, C/N0, carriers, time to first fix,
 * accuracy and how many fixes used L5. Fed on the main thread; the hidden panel in Eu shows it and exports the CSV.
 */
class GnssDiagnostics internal constructor(private val manager: LocationManager?) {
    private val stats = GnssStats()
    private val _summary = MutableStateFlow(stats.summary())
    val summary: StateFlow<GnssSummary> = _summary
    private val _l5Device = MutableStateFlow(capabilityL5())

    /** The chip lists L5 among its signals (Android 14+) or a satellite in a fix used it; null while unknown. */
    val l5Device: StateFlow<Boolean?> = _l5Device

    /** The last status report had an L5/E5a satellite in the fix. */
    var l5InFix = false
        private set
    private var registered = false

    private val callback = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
            val satellites = (0 until status.satelliteCount).map { i ->
                SatSignal(
                    usedInFix = status.usedInFix(i),
                    cn0DbHz = status.getCn0DbHz(i),
                    carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else null,
                )
            }
            l5InFix = L5.inFix(satellites)
            if (l5InFix) _l5Device.value = true
            stats.onStatus(satellites)
            _summary.value = stats.summary()
        }

        override fun onFirstFix(ttffMillis: Int) {
            stats.onFirstFix(ttffMillis.toLong())
            _summary.value = stats.summary()
        }
    }

    @SuppressLint("MissingPermission")
    internal fun start(executor: Executor) {
        val manager = manager ?: return
        if (registered) return
        registered = runCatching { LocationManagerCompat.registerGnssStatusCallback(manager, executor, callback) }.getOrDefault(false)
    }

    internal fun stop() {
        stats.resetWindow()
        if (!registered) return
        registered = false
        l5InFix = false
        manager?.let { runCatching { LocationManagerCompat.unregisterGnssStatusCallback(it, callback) } }
    }

    internal fun onFix(fix: LocationFix) {
        stats.onFix(fix)
        _summary.value = stats.summary()
    }

    internal fun sample(nowMs: Long, batteryPct: Int) {
        stats.sample(nowMs, batteryPct)
    }

    /** Writes the per-minute log as `gnss-<model>-<time>.csv` in [dir]. */
    fun writeCsv(dir: File, nowMs: Long, zone: ZoneId): File {
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(Date(nowMs))
        val name = "gnss-" + model.replace(Regex("[^A-Za-z0-9]+"), "_") + "-$stamp.csv"
        return File(dir, name).apply { writeText(GnssCsv.csv(model, Build.VERSION.SDK_INT, stats.samples(), zone)) }
    }

    private fun capabilityL5(): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val types = runCatching { manager?.gnssCapabilities?.gnssSignalTypes }.getOrNull() ?: return null
        return if (types.isEmpty()) null else L5.supported(types.map { it.carrierFrequencyHz })
    }
}
