package ro.safetyplease.app.platform.ble

import kotlin.math.roundToInt

// a single sample varies by ~6 dB; sort by the recent average
fun smoothRssi(previous: Double?, sample: Int): Double =
    if (previous == null) sample.toDouble() else previous + BleConstants.RSSI_ALPHA * (sample - previous)

/**
 * A long-range phone is heard under two addresses with the same prefix: the legacy set (1M) and the Coded one.
 * The engine gets one: the 1M address while it was heard recently, otherwise the most recent one.
 */
fun preferredAddress(legacy: String?, legacyAt: Long, coded: String?, codedAt: Long, now: Long): String? = when {
    legacy == null -> coded
    coded == null -> legacy
    now - legacyAt <= BleConstants.LEGACY_PREFERENCE_MS -> legacy
    codedAt >= legacyAt -> coded
    else -> legacy
}

/** At most one report per second per address; a result without scan response never hides a full one. */
fun shouldReport(reportedAt: Long?, reportedFull: Boolean, full: Boolean, now: Long): Boolean =
    reportedAt == null || now - reportedAt >= BleConstants.REPORT_WINDOW_MS || (full && !reportedFull)

/** Everything the scan heard, per address and per prefix; decides what reaches the engine as PeerSeen. */
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

    private val tracks = HashMap<String, Track>()
    private val byPrefix = HashMap<Int, Addresses>()
    private var legacyResults = 0
    private var withoutResponse = 0

    /**
     * Records a scan result ([prefix] null = no scan response) and returns the averaged RSSI to report,
     * or null if this result shouldn't be reported.
     */
    fun heard(address: String, prefix: Int?, coded: Boolean, rssi: Int, now: Long): Int? {
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
        if (known != null && byPrefix[known]?.preferred(now) != address) return null
        // without a scan response we don't know the flags; keep what a recent full result reported
        val fullAt = track.fullAt
        if (!full && fullAt != null && now - fullAt < BleConstants.FULL_RESULT_HOLD_MS) return null
        if (!shouldReport(track.reportedAt, track.reportedFull, full, now)) return null
        track.reportedAt = now
        track.reportedFull = full
        return track.rssi?.roundToInt()
    }

    /** Forgets addresses not heard for a minute and returns them, so the radio can clear its state too. */
    fun forget(now: Long): List<String> {
        val gone = tracks.filterValues { now - it.heardAt > BleConstants.SIGHTING_FORGET_MS }.keys.toList()
        gone.forEach(tracks::remove)
        byPrefix.values.removeAll { now - maxOf(it.legacyAt, it.codedAt) > BleConstants.SIGHTING_FORGET_MS }
        return gone
    }

    /** Legacy results since the last call, and how many of them came without a scan response. */
    fun drainStats(): Pair<Int, Int> {
        val stats = legacyResults to withoutResponse
        legacyResults = 0
        withoutResponse = 0
        return stats
    }

    fun clear() {
        tracks.clear()
        byPrefix.clear()
        drainStats()
    }
}
