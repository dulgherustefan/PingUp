package ro.safetyplease.core.ble

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.math.roundToInt

/** How fast the average follows a new sample. */
const val RSSI_ALPHA = 0.3

/** Just under a second, an Android batch: a batch that arrives a few ms early still reports each address. */
const val REPORT_WINDOW_MS = 900L
const val FULL_RESULT_HOLD_MS = 10_000L
const val LEGACY_PREFERENCE_MS = 3_000L
const val SIGHTING_FORGET_MS = 60_000L

/** A scan result of our service; [prefix] is null when the scan response didn't arrive. */
data class Advert(val address: String, val prefix: Int?, val flags: Int, val coded: Boolean, val rssi: Int)

// a single sample varies by ~6 dB; sort by the recent average
fun smoothRssi(previous: Double?, sample: Int): Double =
    if (previous == null) sample.toDouble() else previous + RSSI_ALPHA * (sample - previous)

/**
 * A long-range phone is heard under two addresses with the same prefix: the legacy set (1M) and the Coded one.
 * The engine gets one: the 1M address while it was heard recently, otherwise the most recent one.
 */
fun preferredAddress(legacy: String?, legacyAt: Long, coded: String?, codedAt: Long, now: Long): String? = when {
    legacy == null -> coded
    coded == null -> legacy
    now - legacyAt <= LEGACY_PREFERENCE_MS -> legacy
    codedAt >= legacyAt -> coded
    else -> legacy
}

/** About one report per second per address; a result without scan response never hides a full one. */
fun shouldReport(reportedAt: Long?, reportedFull: Boolean, full: Boolean, now: Long): Boolean =
    reportedAt == null || now - reportedAt >= REPORT_WINDOW_MS || (full && !reportedFull)

/**
 * Everything the scan heard, per address and per prefix; decides what reaches the engine as PeerSeen.
 * It runs on the scan callback thread, before anything is posted to the engine's thread, hence the lock.
 * After [stop] it ignores results until [start]: a scan callback can still arrive after the radio went down.
 */
class Sightings {
    private class Track(var heardAt: Long) {
        var rssi: Double? = null
        var prefix: Int? = null
        var fullAt: Long? = null
        var reportedAt: Long? = null
        var reportedFull = false
    }

    private class Addresses {
        var legacy: String? = null
        var legacyAt = 0L
        var coded: String? = null
        var codedAt = 0L

        fun preferred(now: Long) = preferredAddress(legacy, legacyAt, coded, codedAt, now)
    }

    private val lock = SynchronizedObject()
    private val tracks = HashMap<String, Track>()
    private val byPrefix = HashMap<Int, Addresses>()
    private var legacyResults = 0
    private var withoutResponse = 0
    private var on = true

    /**
     * Records a scan result ([prefix] null = no scan response) and returns the averaged RSSI to report,
     * or null if this result shouldn't be reported.
     */
    fun heard(address: String, prefix: Int?, coded: Boolean, rssi: Int, now: Long): Int? = synchronized(lock) {
        if (!on) return@synchronized null
        val track = tracks.getOrPut(address) { Track(now) }
        track.heardAt = now
        track.rssi = smoothRssi(track.rssi, rssi)
        val full = prefix != null
        if (!coded) {
            legacyResults++
            if (!full) withoutResponse++
        }
        if (prefix != null) {
            track.prefix = prefix
            track.fullAt = now
            val addresses = byPrefix.getOrPut(prefix) { Addresses() }
            if (coded) {
                addresses.coded = address
                addresses.codedAt = now
            } else {
                addresses.legacy = address
                addresses.legacyAt = now
            }
        }
        val known = track.prefix
        if (known != null && byPrefix[known]?.preferred(now) != address) return@synchronized null
        // without a scan response we don't know the flags; keep what a recent full result reported
        val fullAt = track.fullAt
        if (!full && fullAt != null && now - fullAt < FULL_RESULT_HOLD_MS) return@synchronized null
        if (!shouldReport(track.reportedAt, track.reportedFull, full, now)) return@synchronized null
        track.reportedAt = now
        track.reportedFull = full
        track.rssi?.roundToInt()
    }

    /**
     * The results of one scan callback, single or batched. Every result feeds the averages and the statistics;
     * only the ones to report come back, with the averaged RSSI.
     */
    fun heard(batch: List<Advert>, now: Long): List<Advert> = synchronized(lock) {
        batch.mapNotNull { advert -> heard(advert.address, advert.prefix, advert.coded, advert.rssi, now)?.let { advert.copy(rssi = it) } }
    }

    /** Forgets addresses not heard for a minute and returns them, so the radio can clear its state too. */
    fun forget(now: Long): List<String> = synchronized(lock) {
        val gone = tracks.filterValues { now - it.heardAt > SIGHTING_FORGET_MS }.keys.toList()
        gone.forEach(tracks::remove)
        byPrefix.values.removeAll { now - maxOf(it.legacyAt, it.codedAt) > SIGHTING_FORGET_MS }
        gone
    }

    /** Legacy results since the last call, and how many of them came without a scan response. */
    fun drainStats(): Pair<Int, Int> = synchronized(lock) {
        val stats = legacyResults to withoutResponse
        legacyResults = 0
        withoutResponse = 0
        stats
    }

    fun start() = synchronized(lock) {
        on = true
    }

    /** Forgets everything heard. */
    fun stop() = synchronized(lock) {
        on = false
        tracks.clear()
        byPrefix.clear()
        drainStats()
    }
}
