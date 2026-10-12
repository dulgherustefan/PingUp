package ro.safetyplease.core.metrics

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.sortedByKey

/**
 * What the mesh did in one period: the counts are differences between the engine's totals at both ends, the links,
 * peers and reports held are as they were at [at]. No peer is named.
 */
@Serializable
data class MeshSnapshot(
    val at: Long,
    val periodMs: Long,
    val role: Role,
    val links: Int,
    val visiblePeers: Int,
    val cachedReports: Int,
    val sent: Long = 0,
    val received: Long = 0,
    val relayed: Long = 0,
    val dropped: Long = 0,
    val queueFull: Long = 0,
    val storeAndForward: Long = 0,
    val refusedBanned: Long = 0,
    val linksOpened: Long = 0,
    val linksClosed: Long = 0,
    val sentByType: Map<Int, Long> = emptyMap(),
    val receivedByType: Map<Int, Long> = emptyMap(),
    val drops: Map<DropReason, Long> = emptyMap(),
    val reportHops: Map<Int, Long> = emptyMap(),
    val ackHops: Map<Int, Long> = emptyMap(),
) {
    fun perHour(count: Long): Double = if (periodMs <= 0) 0.0 else count * 3_600_000.0 / periodMs

    companion object {
        fun between(before: MeshState, after: MeshState, at: Long, periodMs: Long, role: Role): MeshSnapshot {
            val b = before.counters
            val a = after.counters
            return MeshSnapshot(
                at, periodMs, role, after.readyLinks, after.visiblePeers, after.cachedReports,
                after.sent - before.sent, after.received - before.received, after.relayed - before.relayed,
                after.dropped - before.dropped, after.queueFull - before.queueFull,
                a.storeAndForward - b.storeAndForward, a.refusedBanned - b.refusedBanned,
                a.linksOpened - b.linksOpened, a.linksClosed - b.linksClosed,
                a.sentByType.since(b.sentByType), a.receivedByType.since(b.receivedByType), a.drops.since(b.drops),
                a.reportHops.since(b.reportHops), a.ackHops.since(b.ackHops),
            )
        }

        private fun <K : Comparable<K>> Map<K, Long>.since(before: Map<K, Long>): Map<K, Long> =
            entries.mapNotNull { (k, v) -> (v - (before[k] ?: 0L)).takeIf { it != 0L }?.let { k to it } }.toMap().sortedByKey()
    }
}

/** The snapshots of the last days, oldest first; older ones go once there are [MAX_SNAPSHOTS]. */
@Serializable
data class MetricsLog(val snapshots: List<MeshSnapshot> = emptyList()) {
    fun with(snapshot: MeshSnapshot): MetricsLog = copy(snapshots = (snapshots + snapshot).takeLast(MAX_SNAPSHOTS))

    companion object {
        const val SCHEMA_VERSION = 1

        /** Three days at one every [MeshMetrics.INTERVAL_MS]. */
        const val MAX_SNAPSHOTS = 864
    }
}

/** Appends a [MeshSnapshot] each time it is called, normally every [INTERVAL_MS], for as long as the process lives. */
class MeshMetrics(private val store: JsonStore<MetricsLog>, private val clock: Clock) {
    private var previous = MeshState()
    private var previousAt = clock.monoMs()

    fun snapshot(state: MeshState, role: Role) {
        val now = clock.monoMs()
        val snapshot = MeshSnapshot.between(previous, state, clock.wallMs(), now - previousAt, role)
        store.update { it.with(snapshot) }
        previous = state
        previousAt = now
    }

    companion object {
        const val INTERVAL_MS = 5 * 60_000L
    }
}
