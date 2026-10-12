package ro.safetyplease.app.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.telephony.CellInfo
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.Probe
import ro.safetyplease.core.metrics.Transport
import java.net.HttpURLConnection
import java.net.URL

/**
 * The mobile network as a staff phone sees it, for the pilot's log. No runtime permission: the signal strength needs
 * no READ_PHONE_STATE (Android 9 and up; older phones log no signal), and the network state and the request need only
 * install-time permissions. Nothing here knows who the phone is.
 */
class MobileNetwork(context: Context) {
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)
    private val connectivity: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    private class Signal(val level: Int, val dbm: Int?, val technology: String)

    /** Blocks for up to [PROBE_TIMEOUT_MS] twice while the request runs; never on the main thread. */
    fun sample(at: Long, grid: String): NetworkSample {
        val network = connectivity?.activeNetwork
        val caps = network?.let { connectivity?.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> Transport.NONE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            else -> Transport.OTHER
        }
        val signal = signal()
        // over Wi-Fi the request would say nothing about the mobile network
        val probe = if (network != null && transport == Transport.CELLULAR) probe(network) else null
        return NetworkSample(
            at, grid, signal?.level, signal?.dbm, signal?.technology.orEmpty(), transport,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            probe = probe?.first ?: Probe.NOT_RUN, probeMs = probe?.second,
        )
    }

    private fun signal(): Signal? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val strength = runCatching { telephony?.signalStrength }.getOrNull() ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Signal(strength.level, null, "")
        val cell = strength.cellSignalStrengths.firstOrNull()
        return Signal(strength.level, cell?.dbm?.takeIf { it != CellInfo.UNAVAILABLE }, cell?.let(::technology).orEmpty())
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun technology(cell: CellSignalStrength): String = when (cell) {
        is CellSignalStrengthNr -> "nr"
        is CellSignalStrengthLte -> "lte"
        is CellSignalStrengthWcdma -> "wcdma"
        is CellSignalStrengthTdscdma -> "tdscdma"
        is CellSignalStrengthGsm -> "gsm"
        is CellSignalStrengthCdma -> "cdma"
        else -> ""
    }

    /** A HEAD request over the mobile network, and how long it took to answer or to fail. */
    private fun probe(network: Network): Pair<Probe, Long> {
        val start = SystemClock.elapsedRealtime()
        val ok = runCatching {
            val connection = network.openConnection(URL(PROBE_URL)) as HttpURLConnection
            try {
                connection.requestMethod = "HEAD"
                connection.connectTimeout = PROBE_TIMEOUT_MS
                connection.readTimeout = PROBE_TIMEOUT_MS
                connection.useCaches = false
                connection.instanceFollowRedirects = false
                connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
        return (if (ok) Probe.OK else Probe.FAILED) to SystemClock.elapsedRealtime() - start
    }

    companion object {
        /**
         * Android's own connectivity check, which answers 204 with no body. Android asks the same address itself, so
         * the request tells that server nothing it doesn't already see. A captive portal answers otherwise; a network
         * that fails, not at all.
         */
        const val PROBE_URL = "https://connectivitycheck.gstatic.com/generate_204"
        const val PROBE_TIMEOUT_MS = 5_000
    }
}
