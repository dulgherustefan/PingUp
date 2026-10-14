package ro.safetyplease.core.mesh

import ro.safetyplease.core.crypto.KeyChain
import kotlin.math.abs

/**
 * How far this phone's wall clock is from its neighbours'. A neighbour's clock is read from the header of the notice
 * summaries it sends, link-local packets made just before they leave. The median over the neighbours heard in the last
 * [WINDOW_MS] counts once there are [MIN_PEERS] of them, and only past the skew certificates allow: within it, the
 * phone's own clock decides. The header isn't signed, so one neighbour alone moves nothing.
 */
class PeerClocks {
    /** By peer: its clock minus ours (ms), and when we heard it (monotonic ms). */
    private val heard = LinkedHashMap<Long, LongArray>()

    fun heard(peerId: Long, peerWallMs: Long, wallMs: Long, nowMs: Long) {
        heard.remove(peerId)
        heard[peerId] = longArrayOf(peerWallMs - wallMs, nowMs)
        while (heard.size > MAX_PEERS) heard.remove(heard.keys.first())
    }

    /** The neighbours' clock minus this phone's, in milliseconds; 0 while they agree within the skew or are too few. */
    fun offsetMs(nowMs: Long): Long {
        val offsets = heard.values.filter { nowMs - it[1] <= WINDOW_MS }.map { it[0] }.sorted()
        if (offsets.size < MIN_PEERS) return 0
        val median = offsets[offsets.size / 2]
        return if (abs(median) > KeyChain.SKEW_S * 1000) median else 0
    }

    companion object {
        const val WINDOW_MS = 30 * 60_000L
        const val MIN_PEERS = 3
        private const val MAX_PEERS = 32
    }
}
