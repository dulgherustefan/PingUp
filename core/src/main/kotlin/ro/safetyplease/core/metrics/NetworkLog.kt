package ro.safetyplease.core.metrics

import kotlinx.serialization.Serializable

/** What carries the phone's data at the moment of the sample. */
@Serializable
enum class Transport { NONE, CELLULAR, WIFI, OTHER }

/** The small request that checks whether mobile data works; it runs only over a mobile network. */
@Serializable
enum class Probe { NOT_RUN, OK, FAILED }

/** One look at the mobile network, on a staff phone that opted in. Where it was is only a square of the site grid. */
@Serializable
data class NetworkSample(
    val at: Long,
    /** Empty without a fresh position. */
    val grid: String = "",
    /** Signal bars, 0 to 4, as Android shows them; null when the phone doesn't say. */
    val level: Int? = null,
    val dbm: Int? = null,
    /** The serving cell's radio: lte, nr, wcdma, gsm, cdma, tdscdma; empty when unknown. */
    val technology: String = "",
    val transport: Transport = Transport.NONE,
    /** Android itself found that this network reaches the internet. */
    val validated: Boolean = false,
    val probe: Probe = Probe.NOT_RUN,
    val probeMs: Long? = null,
)

/** The samples of the last days, oldest first; older ones go once there are [MAX_SAMPLES]. */
@Serializable
data class NetworkLog(val samples: List<NetworkSample> = emptyList()) {
    fun with(sample: NetworkSample): NetworkLog = copy(samples = (samples + sample).takeLast(MAX_SAMPLES))

    companion object {
        const val SCHEMA_VERSION = 1
        const val INTERVAL_MS = 2 * 60_000L

        /** Three days at one every [INTERVAL_MS]. */
        const val MAX_SAMPLES = 2_160
    }
}
