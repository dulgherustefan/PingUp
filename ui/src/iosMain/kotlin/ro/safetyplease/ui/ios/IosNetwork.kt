package ro.safetyplease.ui.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreTelephony.CTRadioAccessTechnologyCDMA1x
import platform.CoreTelephony.CTRadioAccessTechnologyCDMAEVDORev0
import platform.CoreTelephony.CTRadioAccessTechnologyCDMAEVDORevA
import platform.CoreTelephony.CTRadioAccessTechnologyCDMAEVDORevB
import platform.CoreTelephony.CTRadioAccessTechnologyEdge
import platform.CoreTelephony.CTRadioAccessTechnologyGPRS
import platform.CoreTelephony.CTRadioAccessTechnologyHSDPA
import platform.CoreTelephony.CTRadioAccessTechnologyHSUPA
import platform.CoreTelephony.CTRadioAccessTechnologyLTE
import platform.CoreTelephony.CTRadioAccessTechnologyNR
import platform.CoreTelephony.CTRadioAccessTechnologyNRNSA
import platform.CoreTelephony.CTRadioAccessTechnologyWCDMA
import platform.CoreTelephony.CTRadioAccessTechnologyeHRPD
import platform.CoreTelephony.CTTelephonyNetworkInfo
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLRequestReloadIgnoringLocalCacheData
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Network.nw_interface_type_cellular
import platform.Network.nw_interface_type_wifi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_uses_interface_type
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.Probe
import ro.safetyplease.core.metrics.Transport
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume

/**
 * The mobile network as iOS lets an app see it: the network it routes through and the radio's technology, not the
 * signal. Over mobile data a small request to Apple's own check tells whether the data works, as the Android app asks
 * Android's; over Wi-Fi it would say nothing about the mobile network, so it doesn't run.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosNetwork {
    @Volatile private var transport = Transport.NONE

    @Volatile private var usable = false

    init {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            usable = nw_path_get_status(path) == nw_path_status_satisfied
            transport = when {
                !usable -> Transport.NONE
                nw_path_uses_interface_type(path, nw_interface_type_cellular) -> Transport.CELLULAR
                nw_path_uses_interface_type(path, nw_interface_type_wifi) -> Transport.WIFI
                else -> Transport.OTHER
            }
        }
        nw_path_monitor_set_queue(monitor, dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u))
        nw_path_monitor_start(monitor)
    }

    suspend fun sample(at: Long, grid: String): NetworkSample {
        val via = transport
        val radio = CTTelephonyNetworkInfo().serviceCurrentRadioAccessTechnology?.values?.firstOrNull() as? String
        val probe = if (via == Transport.CELLULAR) probe() else null
        // iOS has no validation of its own to tell; a path it can use is the nearest it says
        return NetworkSample(
            at, grid, technology = technology(radio), transport = via, validated = usable,
            probe = probe?.first ?: Probe.NOT_RUN, probeMs = probe?.second,
        )
    }

    /** Apple's own check answers "Success"; a captive portal answers with its page, a network that fails not at all. */
    private suspend fun probe(): Pair<Probe, Long> = suspendCancellableCoroutine { done ->
        val start = IosClock.monoMs()
        val configuration = NSURLSessionConfiguration.ephemeralSessionConfiguration.apply { timeoutIntervalForRequest = PROBE_TIMEOUT_S }
        val session = NSURLSession.sessionWithConfiguration(configuration)
        val request = NSURLRequest.requestWithURL(NSURL.URLWithString(PROBE_URL)!!, NSURLRequestReloadIgnoringLocalCacheData, PROBE_TIMEOUT_S)
        val task = session.dataTaskWithRequest(request) { data, response, error ->
            val answer = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() }.orEmpty()
            val ok = error == null && (response as? NSHTTPURLResponse)?.statusCode == 200L && "Success" in answer
            session.finishTasksAndInvalidate()
            done.resume((if (ok) Probe.OK else Probe.FAILED) to IosClock.monoMs() - start)
        }
        done.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    private companion object {
        /** The address iOS itself asks to find a captive portal, so the request tells Apple nothing new. */
        const val PROBE_URL = "https://captive.apple.com/hotspot-detect.html"
        const val PROBE_TIMEOUT_S = 5.0

        fun technology(radio: String?): String = when (radio) {
            CTRadioAccessTechnologyLTE -> "lte"
            CTRadioAccessTechnologyNR, CTRadioAccessTechnologyNRNSA -> "nr"
            CTRadioAccessTechnologyWCDMA, CTRadioAccessTechnologyHSDPA, CTRadioAccessTechnologyHSUPA -> "wcdma"
            CTRadioAccessTechnologyGPRS, CTRadioAccessTechnologyEdge -> "gsm"
            CTRadioAccessTechnologyCDMA1x, CTRadioAccessTechnologyCDMAEVDORev0, CTRadioAccessTechnologyCDMAEVDORevA,
            CTRadioAccessTechnologyCDMAEVDORevB, CTRadioAccessTechnologyeHRPD,
            -> "cdma"
            else -> ""
        }
    }
}
