package ro.safetyplease.core.mesh

import kotlinx.serialization.Serializable

/**
 * [IncidentCache] as saved to disk, so that a relay or an anchor the system kills doesn't lose the reports it was
 * carrying. Times are on the wall clock, which survives a reboot; the monotonic one doesn't.
 */
@Serializable
data class CachedIncidents(val incidents: List<CachedIncident> = emptyList(), val notices: List<CachedNotice> = emptyList())

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
    val response: CachedPacket? = null,
    /** The console's timestamp of the latest response seen, kept after the packet expires; 0 for none. */
    val responseVersion: Long = 0,
    val update: CachedPacket? = null,
    /** The highest update sequence number seen, kept after the packet expires; 0 for none. */
    val updateVersion: Long = 0,
    /** A routine report, which nobody acknowledges. */
    val routine: Boolean = false,
)

/** [frame] is the packet as it goes on the wire, unfragmented, in hex. */
@Serializable
data class CachedPacket(val frame: String, val at: Long, val hops: Int = 0)

/** The latest notice of a [topic], kept until [until] (wall clock, ms); [version] is its sequence number, kept after it expires. */
@Serializable
data class CachedNotice(val topic: Long, val notice: CachedPacket? = null, val version: Long = 0, val until: Long = 0)
