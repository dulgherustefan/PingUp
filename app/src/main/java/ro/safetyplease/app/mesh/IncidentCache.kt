package ro.safetyplease.app.mesh

import ro.safetyplease.app.core.toLong
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.IncidentAck
import ro.safetyplease.app.protocol.Limits
import ro.safetyplease.app.protocol.Packet
import ro.safetyplease.app.protocol.RequestEntry
import ro.safetyplease.app.protocol.SummaryEntry

/**
 * Store-and-forward pentru incidente si ACK-uri. Tine pachetele [retentionMs] de la primire si,
 * dupa aceea, doar o urma a incidentului, ca nodul sa nu-l mai ceara si retransmita la nesfarsit.
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
        var ackTimestamp = 0L
        var ackTeam = ""
        var touchedMs = 0L
    }

    private val entries = LinkedHashMap<Long, Entry>()

    val reportCount: Int get() = entries.values.count { it.report != null }

    private fun entry(id8: Long, nowMs: Long): Entry {
        val e = entries.getOrPut(id8) { Entry(id8) }
        e.touchedMs = nowMs
        return e
    }

    /** Retine raportul daca incidentul e nou. [packet] trebuie sa aiba deja ttl-ul cu care va fi retrimis. */
    fun offerReport(incidentId: ByteArray, packet: Packet, nowMs: Long, hops: Int = 0): Boolean {
        purge(nowMs)
        val e = entry(incidentId.toLong(), nowMs)
        if (e.reportSeen) return false
        e.reportSeen = true
        e.report = Stored(packet, nowMs, hops)
        val stored = entries.values.filter { it.report != null }
        if (stored.size > maxReports) stored.minByOrNull { it.report!!.atMs }?.report = null
        return true
    }

    /** Retine ACK-ul doar daca e mai bun decat cel cunoscut: status mai mare, apoi cel mai vechi, apoi echipa. */
    fun offerAck(ack: IncidentAck, packet: Packet, nowMs: Long): Boolean {
        purge(nowMs)
        val e = entry(ack.incidentId.toLong(), nowMs)
        if (!isBetter(ack, e)) return false
        e.ackStatus = ack.status
        e.ackTimestamp = ack.timestamp
        e.ackTeam = ack.teamName
        e.ack = Stored(packet, nowMs)
        return true
    }

    private fun isBetter(ack: IncidentAck, e: Entry): Boolean {
        if (ack.status != e.ackStatus) return ack.status > e.ackStatus
        if (ack.timestamp != e.ackTimestamp) return ack.timestamp < e.ackTimestamp
        return ack.teamName < e.ackTeam
    }

    fun ackStatus(incidentId: ByteArray): Int = entries[incidentId.toLong()]?.ackStatus ?: AckStatus.NONE

    fun summary(nowMs: Long): List<SummaryEntry> {
        purge(nowMs)
        return entries.values
            .filter { it.report != null || it.ack != null }
            .sortedByDescending { maxOf(it.report?.atMs ?: 0, it.ack?.atMs ?: 0) }
            .take(Limits.SUMMARY_ENTRIES)
            .map { SummaryEntry(it.id8, it.report != null, if (it.ack != null) it.ackStatus else AckStatus.NONE) }
    }

    /** Ce merita cerut de la un peer care a anuntat [remote]. */
    fun missing(remote: List<SummaryEntry>, nowMs: Long): List<RequestEntry> {
        purge(nowMs)
        return remote.mapNotNull { r ->
            val local = entries[r.id8]
            val wantReport = r.hasReport && (local == null || !local.reportSeen)
            val wantAck = r.ackStatus > (local?.ackStatus ?: AckStatus.NONE)
            if (wantReport || wantAck) RequestEntry(r.id8, wantReport, wantAck) else null
        }
    }

    fun report(id8: Long): Packet? = entries[id8]?.report?.packet

    /** Rapoartele inca pastrate, cu numarul de hop-uri la care au fost primite. */
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
