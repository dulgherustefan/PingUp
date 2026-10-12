package ro.safetyplease.core.location

import ro.safetyplease.core.control.LogExport
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One satellite of a GNSS status report. [carrierHz] is null when the chip does not report it. */
data class SatSignal(val usedInFix: Boolean, val cn0DbHz: Float, val carrierHz: Double?)

object L5 {
    /** GPS L5 and Galileo E5a share this carrier. */
    const val CARRIER_HZ = 1_176_450_000.0
    const val TOLERANCE_HZ = 1_000_000.0

    fun isL5(carrierHz: Double): Boolean = abs(carrierHz - CARRIER_HZ) <= TOLERANCE_HZ

    /** The fix used an L5/E5a signal: at least one satellite in the fix reports that carrier. */
    fun inFix(satellites: List<SatSignal>): Boolean = satellites.any { it.usedInFix && it.carrierHz?.let(::isL5) == true }

    /** The chip lists an L5/E5a signal type among what it can track (Android 14+). */
    fun supported(signalCarriersHz: List<Double>): Boolean = signalCarriersHz.any(::isL5)
}

/** One minute of the GNSS diagnostic log; numbers are null when nothing was measured in it. */
data class GnssSample(
    val at: Long,
    val l5Pct: Int?,
    val satsInFix: Float?,
    val cn0Mean: Float?,
    val accMedian: Float?,
    val ttffS: Float?,
    val fixes: Int,
    val batteryPct: Int,
)

/** What the diagnostic panel shows: everything since the location source started. */
data class GnssSummary(
    val satsInFix: Int,
    val cn0Mean: Float?,
    val carriersMhz: List<Double>,
    val ttffS: Float?,
    val accMedian: Float?,
    val accBest: Float?,
    val accWorst: Float?,
    val fixes: Int,
    val l5Pct: Int?,
)

/** Collects GNSS status and fixes for the diagnostic panel and its per-minute CSV. One thread feeds it. */
class GnssStats {
    private var lastStatus: List<SatSignal> = emptyList()
    private val carriers = sortedSetOf<Double>()
    private var ttffMs: Long? = null
    private val allAccuracy = ArrayDeque<Float>()
    private var allFixes = 0
    private var allL5 = 0
    private val windowAccuracy = ArrayList<Float>()
    private val windowSats = ArrayList<Int>()
    private val windowCn0 = ArrayList<Float>()
    private var windowFixes = 0
    private var windowL5 = 0
    private val samples = ArrayDeque<GnssSample>()

    fun onStatus(satellites: List<SatSignal>) {
        lastStatus = satellites
        // rounded to 10 kHz: Doppler moves each satellite's reported carrier by a few kHz
        satellites.mapNotNullTo(carriers) { s -> s.carrierHz?.let { (it / 10_000.0).roundToLong() / 100.0 } }
        val used = satellites.filter { it.usedInFix }
        windowSats += used.size
        if (used.isNotEmpty()) windowCn0 += used.map { it.cn0DbHz }.average().toFloat()
    }

    fun onFirstFix(ttffMs: Long) {
        this.ttffMs = ttffMs
    }

    fun onFix(fix: LocationFix) {
        allFixes++
        windowFixes++
        if (fix.l5) {
            allL5++
            windowL5++
        }
        if (fix.accuracyM > 0f) {
            allAccuracy.addLast(fix.accuracyM)
            windowAccuracy += fix.accuracyM
            if (allAccuracy.size > MAX_ACCURACIES) allAccuracy.removeFirst()
        }
    }

    fun summary(): GnssSummary {
        val used = lastStatus.filter { it.usedInFix }
        return GnssSummary(
            satsInFix = used.size,
            cn0Mean = used.takeIf { it.isNotEmpty() }?.map { it.cn0DbHz }?.average()?.toFloat(),
            carriersMhz = carriers.toList(),
            ttffS = ttffMs?.let { it / 1000f },
            accMedian = median(allAccuracy),
            accBest = allAccuracy.minOrNull(),
            accWorst = allAccuracy.maxOrNull(),
            fixes = allFixes,
            l5Pct = percent(allL5, allFixes),
        )
    }

    /** Closes the current minute into a sample and starts the next one; keeps the last [MAX_SAMPLES]. */
    @Synchronized
    fun sample(nowMs: Long, batteryPct: Int): GnssSample {
        val s = GnssSample(
            at = nowMs,
            l5Pct = percent(windowL5, windowFixes),
            satsInFix = windowSats.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            cn0Mean = windowCn0.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            accMedian = median(windowAccuracy),
            ttffS = ttffMs?.let { it / 1000f },
            fixes = windowFixes,
            batteryPct = batteryPct,
        )
        windowAccuracy.clear()
        windowSats.clear()
        windowCn0.clear()
        windowFixes = 0
        windowL5 = 0
        samples.addLast(s)
        if (samples.size > MAX_SAMPLES) samples.removeFirst()
        return s
    }

    /** Safe to call from another thread, for the export. */
    @Synchronized
    fun samples(): List<GnssSample> = samples.toList()

    private fun percent(part: Int, whole: Int): Int? = if (whole == 0) null else part * 100 / whole

    companion object {
        const val SAMPLE_MS = 60_000L

        /** A day of minutes. */
        const val MAX_SAMPLES = 1_440
        const val MAX_ACCURACIES = 10_000
    }
}

/** The per-model census the field campaign collects: one row per minute of the diagnostic log; [GnssSample.l5Pct] goes in `l5`. */
object GnssCsv {
    val COLUMNS = listOf("model", "api", "l5", "satsInFix", "cn0Mean", "accMedian", "ttffS", "fixes", "batteryPct", "timestamp")

    fun csv(model: String, api: Int, samples: List<GnssSample>, zone: ZoneId): String {
        val csv = LogExport.Csv(COLUMNS)
        for (s in samples) {
            csv.row(
                model, api.toString(), s.l5Pct?.toString().orEmpty(), s.satsInFix.cell(), s.cn0Mean.cell(), s.accMedian.cell(),
                s.ttffS.cell(), s.fixes.toString(), s.batteryPct.toString(), LogExport.time(s.at, zone),
            )
        }
        return csv.toString()
    }

    private fun Float?.cell(): String = this?.let { String.format(Locale.ROOT, "%.1f", it) }.orEmpty()
}
