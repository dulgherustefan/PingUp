package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.AckOrder
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.RequestEntry
import ro.safetyplease.core.protocol.SummaryEntry
import ro.safetyplease.core.util.toLong

/**
 * Store-and-forward for incidents and ACKs. Keeps packets for [retentionMs] after receipt, then only a trace
 * of the incident so the node doesn't request and relay it forever.
 */
class IncidentCache(
    var retentionMs: Long = 30 * 60_000L,
    private val maxReports: Int = 50,
    private val traceMs: Long = 6 * 60 * 60_000L,
    private val maxTraces: Int = 2048,
) {
    private class Stored(val packet: Packet, val atMs: Long, val hops: Int = 0)

    private class Entry(val id8: Long) {
        var reportSeen = false
        var report: Stored? = null
        var ack: Stored? = null
        var ackStatus = AckStatus.NONE
        var ackEpoch = 0
        var ackTimestamp = 0L
        var ackTeam = ""
        var ackAssigned = false
        var touchedMs = 0L

        /** This phone wrote the report. */
        var own = false

        val ownPending: Boolean get() = own && ackStatus < AckStatus.RECEIVED
    }

    private val entries = LinkedHashMap<Long, Entry>()

    val reportCount: Int get() = entries.values.count { it.report != null }

    private fun entry(id8: Long, nowMs: Long): Entry {
        val e = entries.getOrPut(id8) { Entry(id8) }
        e.touchedMs = nowMs
        return e
    }

    /** Keeps the report if the incident is new. [packet] must already carry the ttl it will be relayed with. */
    fun offerReport(incidentId: ByteArray, packet: Packet, nowMs: Long, hops: Int = 0, own: Boolean = false): Boolean {
        purge(nowMs)
        val e = entry(incidentId.toLong(), nowMs)
        if (e.reportSeen) return false
        e.reportSeen = true
        e.own = own
        e.report = Stored(packet, nowMs, hops)
        if (reportCount > maxReports) evictReport()
        return true
    }

    /**
     * Reports staff already has go first, then the oldest. Our own report stays until staff confirms it,
     * so a burst of foreign (or fake) reports can't push it out.
     */
    private fun evictReport() {
        entries.values
            .filter { it.report != null && !it.ownPending }
            .minWithOrNull(compareBy<Entry> { it.ackStatus < AckStatus.RECEIVED }.thenBy { it.report!!.atMs })
            ?.report = null
    }

    fun hasSeenReport(incidentId: ByteArray, nowMs: Long): Boolean {
        purge(nowMs)
        return entries[incidentId.toLong()]?.reportSeen == true
    }

    /** Stops serving the report but keeps the trace, so it isn't requested or accepted again. */
    fun forgetReport(incidentId: ByteArray, nowMs: Long) {
        purge(nowMs)
        val e = entry(incidentId.toLong(), nowMs)
        e.reportSeen = true
        e.report = null
    }

    /**
     * Keeps the ACK only if it beats the known one: later epoch, higher rank, then a take over an assignment, then
     * oldest, then team.
     */
    fun offerAck(ack: IncidentAck, packet: Packet, nowMs: Long): Boolean {
        purge(nowMs)
        val e = entry(ack.incidentId.toLong(), nowMs)
        if (!isBetter(ack, e)) return false
        e.ackStatus = ack.status
        e.ackEpoch = ack.epoch
        e.ackTimestamp = ack.timestamp
        e.ackTeam = ack.teamName
        e.ackAssigned = ack.assigned
        e.ack = Stored(packet, nowMs)
        return true
    }

    private fun isBetter(ack: IncidentAck, e: Entry): Boolean {
        val order = AckOrder.compare(ack.epoch, ack.status, e.ackEpoch, e.ackStatus)
        if (order != 0) return order > 0
        // a team that took the incident has it, whatever the clocks say about the console's assignment
        if (ack.assigned != e.ackAssigned) return !ack.assigned
        if (ack.timestamp != e.ackTimestamp) return ack.timestamp < e.ackTimestamp
        return ack.teamName < e.ackTeam
    }

    fun ackStatus(incidentId: ByteArray): Int = entries[incidentId.toLong()]?.ackStatus ?: AckStatus.NONE

    /** The incidents stored most recently, listing only the packets [servable] lets us hand to this neighbor. */
    fun summary(nowMs: Long, servable: (Packet) -> Boolean = { true }): List<SummaryEntry> {
        purge(nowMs)
        return entries.values
            .mapNotNull { e ->
                val report = e.report?.takeIf { servable(it.packet) }
                val ack = e.ack?.takeIf { servable(it.packet) }
                if (report == null && ack == null) return@mapNotNull null
                val entry = SummaryEntry(
                    e.id8, report != null, if (ack != null) e.ackStatus else AckStatus.NONE, if (ack != null) e.ackEpoch else 0,
                )
                maxOf(report?.atMs ?: 0, ack?.atMs ?: 0) to entry
            }
            .sortedByDescending { it.first }
            .take(Limits.SUMMARY_ENTRIES)
            .map { it.second }
    }

    /** What's worth requesting from a peer that announced [remote]. */
    fun missing(remote: List<SummaryEntry>, nowMs: Long): List<RequestEntry> {
        purge(nowMs)
        return remote.mapNotNull { r ->
            val local = entries[r.id8]
            val localStatus = local?.ackStatus ?: AckStatus.NONE
            // a status from a newer version wouldn't be kept, so asking for it would only repeat every summary
            val wantAck = AckStatus.isKnown(r.ackStatus) && AckOrder.compare(r.epoch, r.ackStatus, local?.ackEpoch ?: 0, localStatus) > 0
            // a cancelled report doesn't need to go anywhere; the ACK that closes it is enough
            val cancelled = (if (wantAck) r.ackStatus else localStatus) == AckStatus.CANCELLED
            val wantReport = r.hasReport && !cancelled && (local == null || !local.reportSeen)
            if (wantReport || wantAck) RequestEntry(r.id8, wantReport, wantAck) else null
        }
    }

    fun report(id8: Long): Packet? = entries[id8]?.report?.packet

    /** Reports still kept, with the hop count they arrived with. */
    fun reports(): List<Pair<Packet, Int>> = entries.values.mapNotNull { e -> e.report?.let { it.packet to it.hops } }

    fun ack(id8: Long): Packet? = entries[id8]?.ack?.packet

    fun clear() = entries.clear()

    private fun purge(nowMs: Long) {
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.report?.let { s -> if (nowMs - s.atMs > retentionMs) e.report = null }
            e.ack?.let { s -> if (nowMs - s.atMs > retentionMs) e.ack = null }
            if (e.report == null && e.ack == null && nowMs - e.touchedMs > traceMs) it.remove()
        }
        if (entries.size > maxTraces) {
            val excess = entries.size - maxTraces
            val victims = entries.values.filter { it.report == null && it.ack == null }
                .sortedBy { it.touchedMs }.take(excess)
            for (v in victims) entries.remove(v.id8)
        }
    }
}
