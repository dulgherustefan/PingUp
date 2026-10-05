package ro.safetyplease.app.ble

import kotlin.math.roundToInt

// un singur esantion variaza cu ~6 dB; ordonam dupa media recenta
fun smoothRssi(previous: Double?, sample: Int): Double =
    if (previous == null) sample.toDouble() else previous + BleConstants.RSSI_ALPHA * (sample - previous)

/**
 * Un telefon cu raza lunga se aude sub doua adrese cu acelasi prefix: setul legacy (1M) si setul Coded.
 * Motorul primeste una singura: cea de pe 1M cat timp a fost auzita recent, altfel cea auzita ultima.
 */
fun preferredAddress(legacy: String?, legacyAt: Long, coded: String?, codedAt: Long, now: Long): String? = when {
    legacy == null -> coded
    coded == null -> legacy
    now - legacyAt <= BleConstants.LEGACY_PREFERENCE_MS -> legacy
    codedAt >= legacyAt -> coded
    else -> legacy
}

/** Cel mult un raport pe secunda per adresa; un rezultat fara scan response nu ascunde unul complet. */
fun shouldReport(reportedAt: Long?, reportedFull: Boolean, full: Boolean, now: Long): Boolean =
    reportedAt == null || now - reportedAt >= BleConstants.REPORT_WINDOW_MS || (full && !reportedFull)

/** Tot ce a auzit scanarea, per adresa si per prefix; decide ce ajunge la motor ca PeerSeen. */
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
     * Inregistreaza un rezultat de scanare ([prefix] null = fara scan response) si intoarce RSSI-ul mediat
     * de raportat, sau null daca rezultatul nu trebuie raportat.
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
        // fara scan response nu stim flag-urile; nu stricam ce a raportat recent un rezultat complet
        val fullAt = track.fullAt
        if (!full && fullAt != null && now - fullAt < BleConstants.FULL_RESULT_HOLD_MS) return null
        if (!shouldReport(track.reportedAt, track.reportedFull, full, now)) return null
        track.reportedAt = now
        track.reportedFull = full
        return track.rssi?.roundToInt()
    }

    /** Uita adresele neauzite de un minut si le intoarce, ca radioul sa-si curete si el starea. */
    fun forget(now: Long): List<String> {
        val gone = tracks.filterValues { now - it.heardAt > BleConstants.SIGHTING_FORGET_MS }.keys.toList()
        gone.forEach(tracks::remove)
        byPrefix.values.removeAll { now - maxOf(it.legacyAt, it.codedAt) > BleConstants.SIGHTING_FORGET_MS }
        return gone
    }

    /** Rezultate legacy si cate dintre ele au venit fara scan response, de la ultimul apel. */
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
