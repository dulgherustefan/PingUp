package ro.safetyplease.core.mesh

import kotlinx.serialization.Serializable

/**
 * [IncidentCache] as saved to disk, so that a relay or an anchor the system kills doesn't lose the reports it was
 * carrying. Times are on the wall clock, which survives a reboot; the monotonic one doesn't.
 */
@Serializable
data class CachedIncidents(val incidents: List<CachedIncident> = emptyList())

@Serializable
data class CachedIncident(
    val id8: Long,
    val reportSeen: Boolean = false,
    val own: Boolean = false,
    val report: CachedPacket? = null,
    val ack: CachedPacket? = null,
    val ackStatus: Int = 0,
    val ackEpoch: Int = 0,
    val ackTimestamp: Long = 0,
    val ackTeam: String = "",
    val ackAssigned: Boolean = false,
    val touchedAt: Long = 0,
)

/** [frame] is the packet as it goes on the wire, unfragmented, in hex. */
@Serializable
data class CachedPacket(val frame: String, val at: Long, val hops: Int = 0)
