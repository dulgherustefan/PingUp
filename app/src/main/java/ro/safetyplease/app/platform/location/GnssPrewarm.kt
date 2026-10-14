package ro.safetyplease.app.platform.location

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ro.safetyplease.app.AppContainer

/**
 * While the phone has working internet, asks the GNSS chip to fetch its predicted orbits (PSDS, called XTRA on Android
 * 8 and 9) and the time, at most every [EVERY_MS]: with them the first fix offline at the festival takes seconds
 * instead of minutes. A network counts once Android has checked it reaches the internet, so not behind a captive
 * portal. The commands always return true, so whether the chip used them cannot be confirmed.
 */
class GnssPrewarm(context: Context) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _online = MutableStateFlow(false)

    /** The default network reaches the internet: the moment to keep the app open so the GNSS data comes down. */
    val online: StateFlow<Boolean> = _online
    private var injectedAtMs: Long? = null

    init {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    inject(force = false)
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }
        // online for days on the same network: the orbits fetched at the start go stale all the same
        scope.launch {
            while (true) {
                delay(CHECK_MS)
                inject(force = false)
            }
        }
    }

    /** Sends the commands while online, once [EVERY_MS] have passed since the last time or at once with [force]. */
    @Synchronized
    fun inject(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!_online.value || (!force && !injectionDue(injectedAtMs, now))) return
        injectedAtMs = now
        for (command in prewarmCommands(Build.VERSION.SDK_INT)) {
            val sent = runCatching { manager?.sendExtraCommand(LocationManager.GPS_PROVIDER, command, null) == true }.getOrDefault(false)
            Log.i(AppContainer.TAG, "gnss: $command ${if (sent) "sent" else "refused"}")
        }
    }

    companion object {
        const val EVERY_MS = 12 * 60 * 60_000L
        private const val CHECK_MS = 30 * 60_000L
    }
}

/** What makes the chip fetch its orbits and the time: Android 8 and 9 know the orbits as XTRA, 10 and later as PSDS. */
internal fun prewarmCommands(sdk: Int): List<String> =
    listOf(if (sdk >= Build.VERSION_CODES.Q) "force_psds_injection" else "force_xtra_injection", "force_time_injection")

/** Never yet, or [GnssPrewarm.EVERY_MS] since the last time, on the monotonic clock. */
internal fun injectionDue(lastAtMs: Long?, nowMs: Long): Boolean = lastAtMs == null || nowMs - lastAtMs >= GnssPrewarm.EVERY_MS
