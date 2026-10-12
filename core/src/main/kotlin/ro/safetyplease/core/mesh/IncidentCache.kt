package ro.safetyplease.core.mesh

import ro.safetyplease.core.protocol.AckOrder
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.HeldEntry
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentResponse
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.RequestEntry
import ro.safetyplease.core.protocol.Summary2Codec
import ro.safetyplease.core.protocol.SummaryEntry
import ro.safetyplease.core.protocol.SummaryKind
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.toLong

/**
 * Store-and-forward for incidents, ACKs, the console's responses to reporters and the reporters' updates. Keeps
 * packets for [retentionMs] after receipt, then only a trace of the incident so the node doesn't request and relay it
 * forever.
 */
class IncidentCache(
    var retentionMs: Long = 30 * 60_000L,
    private val maxReports: Int = 50,
    private val traceMs: Long = 6 * 60 * 60_000L,
    private val maxTraces: Int = 2048,
    private val maxUpdates: Int = 50,
) {
    private class Stored(val packet: Packet, val atMs: Long, val hops: Int = 0)

    /** The latest packet of one kind; [version] is a response's timestamp or an update's sequence number, 0 for none. */
    private class Kept {
        var stored: Stored? = null
        var version = 0L
    }

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
        val response = Kept()
        val update = Kept()

        /** This phone wrote the report. */
        var own = false

        val ownPending: Boolean get() = own && ackStatus < AckStatus.RECEIVED

        val empty: Boolean get() = report == null && ack == null && response.stored == null && update.stored == null

        fun kept(kind: Int): Kept = if (kind == SummaryKind.RESPONSES) response else update
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

    /** Keeps the response if it is the incident's latest by the console's clock, which never gives two the same second. */
    fun offerResponse(response: IncidentResponse, packet: Packet, nowMs: Long): Boolean =
        offer(SummaryKind.RESPONSES, response.incidentId.toLong(), response.timestamp, packet, nowMs)

    /**
     * Keeps the update if its sequence number is the highest seen for the incident. Relays can't check updates, so at
     * most [maxUpdates] are kept: past that, the one kept longest goes, never this phone's own.
     */
    fun offerUpdate(incidentId: ByteArray, seq: Int, packet: Packet, nowMs: Long, own: Boolean = false): Boolean {
        if (!offer(SummaryKind.UPDATES, incidentId.toLong(), seq.toLong(), packet, nowMs)) return false
        if (own) entries.getValue(incidentId.toLong()).own = true
        if (entries.values.count { it.update.stored != null } > maxUpdates) {
            entries.values.filter { it.update.stored != null && !it.own }.minByOrNull { it.update.stored!!.atMs }?.update?.stored = null
        }
        return true
    }

    private fun offer(kind: Int, id8: Long, version: Long, packet: Packet, nowMs: Long): Boolean {
        purge(nowMs)
        val kept = entry(id8, nowMs).kept(kind)
        if (version <= kept.version) return false
        kept.version = version
        kept.stored = Stored(packet, nowMs)
        return true
    }

    /** The responses or updates ([kind]) stored most recently, as many as one SUMMARY2 lists. */
    fun held(kind: Int, nowMs: Long, servable: (Packet) -> Boolean = { true }): List<HeldEntry> {
        purge(nowMs)
        return entries.values
            .mapNotNull { e -> e.kept(kind).takeIf { k -> k.stored?.packet?.let(servable) == true }?.let { e.id8 to it } }
            .sortedByDescending { (_, k) -> k.stored!!.atMs }
            .take(Summary2Codec.maxHeld(kind))
            .map { (id8, k) -> HeldEntry(id8, k.version) }
    }

    /** The id8s of the responses or updates ([kind]) a peer holds a later one of. */
    fun missing(kind: Int, remote: List<HeldEntry>, nowMs: Long): List<Long> {
        purge(nowMs)
        return remote.filter { it.version > (entries[it.id8]?.kept(kind)?.version ?: 0) }.map { it.id8 }.take(Limits.SUMMARY_ENTRIES)
    }

    fun kept(kind: Int, id8: Long): Packet? = entries[id8]?.kept(kind)?.stored?.packet

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

    fun snapshot(nowMs: Long, wallMs: Long): CachedIncidents {
        fun wall(atMs: Long) = wallMs - (nowMs - atMs)
        fun saved(s: Stored?) = s?.let { CachedPacket(PacketCodec.encode(it.packet).toHex(), wall(it.atMs), it.hops) }
        return CachedIncidents(entries.values.map { e ->
            CachedIncident(
                e.id8, e.reportSeen, e.own, saved(e.report), saved(e.ack),
                e.ackStatus, e.ackEpoch, e.ackTimestamp, e.ackTeam, e.ackAssigned, wall(e.touchedMs),
                saved(e.response.stored), e.response.version, saved(e.update.stored), e.update.version,
            )
        })
    }

    /**
     * Takes back what [snapshot] saved, aged by the wall clock. Packets older than [maxAgeMs], the longest any role
     * keeps them, are dropped, and so is a packet that doesn't decode; the role's own retention applies from then on.
     */
    fun restore(saved: CachedIncidents, nowMs: Long, wallMs: Long, maxAgeMs: Long) {
        fun at(wall: Long) = nowMs - maxOf(wallMs - wall, 0)
        fun stored(p: CachedPacket?, type: Int): Stored? {
            if (p == null || wallMs - p.at > maxAgeMs) return null
            val packet = runCatching { PacketCodec.decode(p.frame.hexToBytes()) }.getOrNull()
                ?.takeIf { it.fragment == null && it.packet.type == type }?.packet ?: return null
            return Stored(packet, at(p.at), p.hops)
        }
        entries.clear()
        for (c in saved.incidents) {
            val e = Entry(c.id8)
            e.report = stored(c.report, PacketType.INCIDENT_REPORT)
            e.ack = stored(c.ack, PacketType.INCIDENT_ACK)
            e.response.stored = stored(c.response, PacketType.INCIDENT_RESPONSE)
            e.update.stored = stored(c.update, PacketType.INCIDENT_UPDATE)
            if (e.empty && wallMs - c.touchedAt > traceMs) continue
            e.reportSeen = c.reportSeen
            e.own = c.own
            e.ackStatus = c.ackStatus
            e.ackEpoch = c.ackEpoch
            e.ackTimestamp = c.ackTimestamp
            e.ackTeam = c.ackTeam
            e.ackAssigned = c.ackAssigned
            e.response.version = c.responseVersion
            e.update.version = c.updateVersion
            e.touchedMs = at(c.touchedAt)
            entries[c.id8] = e
        }
    }

    private fun purge(nowMs: Long) {
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.report?.let { s -> if (nowMs - s.atMs > retentionMs) e.report = null }
            e.ack?.let { s -> if (nowMs - s.atMs > retentionMs) e.ack = null }
            for (k in listOf(e.response, e.update)) k.stored?.let { s -> if (nowMs - s.atMs > retentionMs) k.stored = null }
            if (e.empty && nowMs - e.touchedMs > traceMs) it.remove()
        }
        if (entries.size > maxTraces) {
            val excess = entries.size - maxTraces
            val victims = entries.values.filter { it.empty }.sortedBy { it.touchedMs }.take(excess)
            for (v in victims) entries.remove(v.id8)
        }
    }
}
