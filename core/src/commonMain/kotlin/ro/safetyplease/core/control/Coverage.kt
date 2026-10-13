package ro.safetyplease.core.control

import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.MobileReading

/** What the anchors of a square say of mobile data, worst first. */
enum class CellData { NO_NETWORK, FAILS, WORKS, UNKNOWN }

/**
 * What the fresh heartbeats of the anchors in one square of the site grid say: the weakest signal ([level], null if
 * none measured it), the worst [data], and for the mesh the most links and the largest crowd bucket.
 */
data class SquareCoverage(
    val column: Int,
    val row: Int,
    val anchors: Int,
    val level: Int?,
    val data: CellData,
    val links: Int,
    val crowd: Int,
)

/** The coverage overlay of the console. A square without an anchor that placed itself in it has no data. */
object Coverage {
    /** Three heartbeats: an older one says nothing of now. */
    const val FRESH_MS = 15 * 60_000L

    fun data(mobile: MobileReading?): CellData = when {
        mobile == null -> CellData.UNKNOWN
        mobile.transport == MobileReading.TRANSPORT_NONE && (mobile.level ?: 0) == 0 -> CellData.NO_NETWORK
        mobile.probe == MobileReading.PROBE_OK -> CellData.WORKS
        mobile.probe == MobileReading.PROBE_FAILED -> CellData.FAILS
        mobile.transport == MobileReading.TRANSPORT_CELLULAR && mobile.validated -> CellData.WORKS
        else -> CellData.UNKNOWN
    }

    fun squares(peers: Collection<HeardPeer>, nowMs: Long): List<SquareCoverage> = peers
        .mapNotNull { peer ->
            val beat = peer.beat ?: return@mapNotNull null
            val at = peer.beatAtMs ?: return@mapNotNull null
            if (nowMs - at >= FRESH_MS || beat.column == null || beat.row == null) null else beat
        }
        .groupBy { it.column!! to it.row!! }
        .map { (square, beats) -> square(square.first, square.second, beats) }

    private fun square(column: Int, row: Int, beats: List<Heartbeat>) = SquareCoverage(
        column = column,
        row = row,
        anchors = beats.size,
        level = beats.mapNotNull { it.mobile?.level }.minOrNull(),
        data = beats.map { data(it.mobile) }.min(),
        links = beats.maxOf { it.links },
        crowd = beats.maxOf { it.crowd },
    )
}
