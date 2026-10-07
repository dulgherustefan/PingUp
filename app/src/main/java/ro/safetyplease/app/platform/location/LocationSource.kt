package ro.safetyplease.app.platform.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GeoFix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long) {
    fun isFresh(nowMs: Long) = nowMs - timeMs < FRESH_MS

    companion object {
        const val FRESH_MS = 2 * 60_000L
    }
}

/** Position from GPS_PROVIDER, without Play Services or network. Without A-GPS the first fix can take a while. */
class LocationSource(private val context: Context) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val _fix = MutableStateFlow<GeoFix?>(null)
    val fix: StateFlow<GeoFix?> = _fix
    private var listening = false

    private val listener = LocationListener { location ->
        _fix.value = GeoFix(location.latitude, location.longitude, location.accuracy, System.currentTimeMillis())
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        val manager = manager ?: return
        if (listening || !hasPermission()) return
        val available = runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        if (!available) return
        runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, listener, Looper.getMainLooper())
            listening = true
            val last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null && System.currentTimeMillis() - last.time < GeoFix.FRESH_MS) {
                _fix.value = GeoFix(last.latitude, last.longitude, last.accuracy, last.time)
            }
        }
    }

    fun stop() {
        if (!listening) return
        listening = false
        runCatching { manager?.removeUpdates(listener) }
    }
}
