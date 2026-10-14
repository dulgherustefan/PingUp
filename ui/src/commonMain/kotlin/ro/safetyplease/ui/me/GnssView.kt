package ro.safetyplease.ui.me

import kotlinx.coroutines.flow.StateFlow
import okio.Path
import ro.safetyplease.core.location.GnssSummary

/** The GNSS chip as the hidden diagnostic panel shows it, where the phone reports that much: Android does. */
interface GnssView {
    /** What the chip did since the location source started. */
    val summary: StateFlow<GnssSummary>

    /** The chip lists L5 among its signals or a fix used it; null while unknown. */
    val l5Device: StateFlow<Boolean?>

    /** How often a fix is asked for with the screen off while measuring; null when off. */
    val mode: StateFlow<Long?>

    fun setMode(intervalMs: Long?)

    /** No fix for over an hour: the next one may take up to 30 s. */
    val searching: StateFlow<Boolean>

    /** The phone has internet, so the chip can fetch its orbit data now. */
    val online: StateFlow<Boolean>

    /** While online: fetches the orbit data and keeps the chip on a while, so the first fix offline comes fast. */
    fun prepareOffline() = Unit

    /** Writes the per-minute log into [dir] and gives the file. */
    fun writeCsv(dir: Path, nowMs: Long): Path
}
