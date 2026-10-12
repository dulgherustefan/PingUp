package ro.safetyplease.app.platform.location

import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * While the phone still has internet, asks the GNSS chip to download its predicted orbits (PSDS), once per run of the
 * app. With them the first fix offline at the festival takes seconds instead of minutes. The command always returns
 * true, so whether the chip used it cannot be confirmed.
 */
class GnssPrewarm(context: Context) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val _online = MutableStateFlow(false)

    /** The default network reaches the internet: the moment to keep the app open so the GNSS data comes down. */
    val online: StateFlow<Boolean> = _online
    private var injected = false

    init {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    _online.value = internet
                    if (internet && !injected) {
                        injected = true
                        runCatching { manager?.sendExtraCommand(LocationManager.GPS_PROVIDER, PSDS_COMMAND, null) }
                    }
                }

                override fun onLost(network: Network) {
                    _online.value = false
                }
            })
        }
    }

    private companion object {
        const val PSDS_COMMAND = "force_psds_injection"
    }
}
