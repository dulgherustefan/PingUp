package ro.safetyplease.core.mesh

import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeOrder
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
 * forever. The console's notices are kept apart, by the slots of [NoticeOrder], until they expire.
 */
class IncidentCache(
    var retentionMs: Long = 30 * 60_000L,
    private val maxReports: Int = 50,
    private val traceMs: Long = 6 * 60 * 60_000L,
    private val maxTraces: Int = 2048,
    private val maxUpdates: Int = 50,
    private val maxNotices: Int = 24,
) {
    private class Stored(val packet: Packet, val atMs: Long, val hops: Int = 0)

    /** The latest packet of one kind; [version] is a response's timestamp or an update's sequence number, 0 for none. */
    private class Kept {
        var stored: Stored? = null
        var version = 0L
    }

    /**
     * A notice of the console's: served until [untilMs], when it ends here ([NoticeOrder.until]); its [order] still
     * counts in its slot until [markUntilMs], after it ended too.
     */
    private class KeptNotice(val notice: Notice, val packet: Packet, val atMs: Long, val order: Long, val untilMs: Long, val markUntilMs: Long) {
        val held = NoticeOrder.Held(notice, order)
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

        /** A routine report ([settle]): nobody acknowledges it, so it counts as confirmed and stays [ROUTINE_MS]. */
        var routine = false

        /** Staff has it, or nobody waits for staff to have it. */
        val settled: Boolean get() = routine || ackStatus >= AckStatus.RECEIVED

        val ownPending: Boolean get() = own && !settled

        val empty: Boolean get() = report == null && ack == null && response.stored == null && update.stored == null

        fun kept(kind: Int): Kept = if (kind == SummaryKind.RESPONSES) response else update
    }

    private val entries = LinkedHashMap<Long, Entry>()

    /** By slot ([NoticeOrder.slot]). */
    private val notices = HashMap<Int, MutableList<KeptNotice>>()

    /**
     * Notices refused or let go lately, by [NoticeOrder.topicKey] and [NoticeOrder.extraId], with when to forget them: a
     * neighbour that still lists one isn't asked for it again meanwhile.
     */
    private val noticesDone = LinkedHashMap<Long, Long>()

    /** The monotonic time of the last purge, for what is served without one. */
    private var purgedAtMs = Long.MIN_VALUE

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
            .minWithOrNull(compareBy<Entry> { !it.settled }.thenBy { it.report!!.atMs })
            ?.report = null
    }

    /**
     * Marks a report as routine, as a staff phone that opened it (or wrote it) knows: a crowd level nobody acknowledges.
     * It goes first when the cache is full and after [ROUTINE_MS]. A relay that can't open it keeps it as any other.
     */
    fun settle(incidentId: ByteArray, nowMs: Long) {
        purge(nowMs)
        entry(incidentId.toLong(), nowMs).routine = true
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

    /** The highest sequence number held in [topic], in any of its slots, whether the notice still holds or not; 0 for none. */
    fun noticeSeq(topic: Int): Long = notices.filterKeys { NoticeOrder.topicOf(it) == topic }.values.flatten().maxOfOrNull { it.notice.seq } ?: 0

    /** The order of the newest notice held in [slot] ([NoticeOrder.orderTime]); 0 for none. */
    fun noticeOrder(slot: Int): Long = notices[slot]?.maxOfOrNull { it.order } ?: 0

    private fun noticeTake(notice: Notice, wallMs: Long): NoticeOrder.Take? {
        val seen = wallMs / 1000
        if (NoticeOrder.until(notice, seen) <= seen) return null
        val held = notices[NoticeOrder.slot(notice)].orEmpty().map { it.held }
        return NoticeOrder.take(notice, NoticeOrder.orderTime(notice, seen), held).takeIf { it.taken }
    }

    /** Whether [notice] would be kept at [wallMs], this phone's clock as notices read it; one that wouldn't goes no further. */
    fun takesNotice(notice: Notice, nowMs: Long, wallMs: Long): Boolean {
        purge(nowMs)
        return noticeTake(notice, wallMs) != null
    }

    /**
     * Keeps the notice if its slot takes it ([NoticeOrder.take]) and it still holds at [wallMs]. Notices are signed by a
     * control shift, so they aren't many; past [maxNotices] the one kept longest goes, those that ended first.
     */
    fun offerNotice(notice: Notice, packet: Packet, nowMs: Long, wallMs: Long): Boolean {
        purge(nowMs)
        val take = noticeTake(notice, wallMs)
        if (take == null) {
            letGo(notice, nowMs)
            return false
        }
        val list = notices.getOrPut(NoticeOrder.slot(notice)) { mutableListOf() }
        take.ends.sortedDescending().forEach { letGo(list.removeAt(it).notice, nowMs) }
        val seen = wallMs / 1000
        val order = NoticeOrder.orderTime(notice, seen)
        val kept = KeptNotice(
            notice, packet, nowMs, order, nowMs + (NoticeOrder.until(notice, seen) - seen) * 1000,
            nowMs + (NoticeOrder.markUntil(order) - seen) * 1000,
        )
        list += kept
        val all = notices.values.flatten()
        if (all.size > maxNotices) {
            all.filter { it !== kept }.minWithOrNull(compareBy<KeptNotice> { it.untilMs > nowMs }.thenBy { it.atMs })?.let { old ->
                notices.values.forEach { it.remove(old) }
                letGo(old.notice, nowMs)
            }
        }
        return true
    }

    /** A notice the key chain refused: a neighbour that lists it isn't asked for it again for a while. */
    fun refuseNotice(notice: Notice, nowMs: Long) {
        purge(nowMs)
        letGo(notice, nowMs)
    }

    /** Drops the notices whose certificate [revoked] says was revoked since, with their order. */
    fun forgetNotices(revoked: (Long) -> Boolean) {
        for (list in notices.values) list.removeAll { revoked(it.notice.certificate.id) }
        notices.values.removeAll { it.isEmpty() }
    }

    /** Forgets which notices were refused: the phone's clock reads them differently now. */
    fun forgetRefusedNotices() = noticesDone.clear()

    private fun letGo(notice: Notice, nowMs: Long) {
        for (key in listOf(NoticeOrder.topicKey(notice.topic, notice.seq), NoticeOrder.extraId(notice))) {
            noticesDone.remove(key)
            noticesDone[key] = nowMs + NOTICE_DONE_MS
        }
        while (noticesDone.size > MAX_NOTICES_DONE) noticesDone.remove(noticesDone.keys.first())
    }

    private fun liveNotices(nowMs: Long): List<KeptNotice> = notices.values.flatten().filter { it.untilMs > nowMs }

    /** A topic's notice listed by its topic, as earlier versions do: the highest sequence number, an evacuation first. */
    private fun primary(live: List<KeptNotice>, topic: Int): KeptNotice? =
        live.filter { it.notice.topic == topic }
            .maxWithOrNull(compareBy<KeptNotice> { it.notice.seq }.thenBy { NoticeOrder.slot(it.notice) == NoticeOrder.EVACUATIONS }.thenBy { it.order })

    private fun knowsNotice(key: Long): Boolean =
        key in noticesDone || notices.values.any { list -> list.any { NoticeOrder.topicKey(it.notice.topic, it.notice.seq) == key || NoticeOrder.extraId(it.notice) == key } }

    /**
     * The notices this phone holds, as a SUMMARY2 lists them (PROTOCOL.md, section 6.2): each topic's highest by its topic
     * and sequence number, first, so phones of earlier versions find it; the others by [NoticeOrder.extraId], version 0.
     */
    private fun heldNotices(nowMs: Long, servable: (Packet) -> Boolean): List<HeldEntry> {
        val live = liveNotices(nowMs).filter { servable(it.packet) }
        val primaries = live.map { it.notice.topic }.distinct().mapNotNull { primary(live, it) }
        val byTopic = primaries.sortedByDescending { it.atMs }.map { HeldEntry(it.notice.topic.toLong(), it.notice.seq) }
        val others = live.filter { it !in primaries }.sortedByDescending { it.atMs }.map { HeldEntry(NoticeOrder.extraId(it.notice), 0) }
        return (byTopic + others).take(Summary2Codec.maxHeld(SummaryKind.NOTICES))
    }

    /** What one kind holds, by id8; not for notices. */
    private fun slots(kind: Int): Map<Long, Kept> = entries.mapValues { (_, e) -> e.kept(kind) }

    /** The responses, updates or notices ([kind]) stored most recently, as many as one SUMMARY2 lists. */
    fun held(kind: Int, nowMs: Long, servable: (Packet) -> Boolean = { true }): List<HeldEntry> {
        purge(nowMs)
        if (kind == SummaryKind.NOTICES) return heldNotices(nowMs, servable)
        return slots(kind).entries
            .filter { (_, k) -> k.stored?.packet?.let(servable) == true }
            .sortedByDescending { (_, k) -> k.stored!!.atMs }
            .take(Summary2Codec.maxHeld(kind))
            .map { (id8, k) -> HeldEntry(id8, k.version) }
    }

    /**
     * The id8s of the responses, updates or notices ([kind]) a peer holds a later one of. Of notices, those this phone
     * neither holds nor refused lately, whatever their number: a later issue time can come with a lower one.
     */
    fun missing(kind: Int, remote: List<HeldEntry>, nowMs: Long): List<Long> {
        purge(nowMs)
        if (kind == SummaryKind.NOTICES) {
            return remote.filter { e ->
                when {
                    NoticeOrder.isExtraId(e.id8) -> !knowsNotice(e.id8)
                    e.id8 in 1L..0xFFL && e.version > 0 -> !knowsNotice(NoticeOrder.topicKey(e.id8.toInt(), e.version))
                    else -> false
                }
            }.map { it.id8 }.take(Limits.SUMMARY_ENTRIES)
        }
        val local = slots(kind)
        return remote.filter { it.version > (local[it.id8]?.version ?: 0) }.map { it.id8 }.take(Limits.SUMMARY_ENTRIES)
    }

    fun kept(kind: Int, id8: Long): Packet? {
        if (kind != SummaryKind.NOTICES) return slots(kind)[id8]?.stored?.packet
        val live = liveNotices(purgedAtMs)
        val kept = if (NoticeOrder.isExtraId(id8)) live.firstOrNull { NoticeOrder.extraId(it.notice) == id8 } else primary(live, id8.toInt())
        return kept?.packet
    }

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

    fun clear() {
        entries.clear()
        notices.clear()
        noticesDone.clear()
    }

    fun snapshot(nowMs: Long, wallMs: Long): CachedIncidents {
        fun wall(atMs: Long) = wallMs - (nowMs - atMs)
        fun saved(s: Stored?) = s?.let { CachedPacket(PacketCodec.encode(it.packet).toHex(), wall(it.atMs), it.hops) }
        return CachedIncidents(entries.values.map { e ->
            CachedIncident(
                e.id8, e.reportSeen, e.own, saved(e.report), saved(e.ack),
                e.ackStatus, e.ackEpoch, e.ackTimestamp, e.ackTeam, e.ackAssigned, wall(e.touchedMs),
                saved(e.response.stored), e.response.version, saved(e.update.stored), e.update.version, e.routine,
            )
        }, notices.values.flatten().map { k ->
            CachedNotice(
                k.notice.topic.toLong(), saved(Stored(k.packet, k.atMs)), k.notice.seq, wall(k.untilMs), k.order, wall(k.markUntilMs),
            )
        })
    }

    /**
     * Takes back what [snapshot] saved, aged by the wall clock. Packets older than [maxAgeMs], the longest any role
     * keeps them, are dropped, and so is a packet that doesn't decode; the role's own retention applies from then on.
     * A notice is kept until it expires, however old, and its order as long as it counts; a sequence number saved
     * without its notice, as earlier versions kept one forever, is let go.
     */
    fun restore(saved: CachedIncidents, nowMs: Long, wallMs: Long, maxAgeMs: Long) {
        fun at(wall: Long) = nowMs - maxOf(wallMs - wall, 0)
        fun stored(p: CachedPacket?, type: Int, maxAge: Long = Long.MAX_VALUE): Stored? {
            if (p == null || wallMs - p.at > maxAge) return null
            val packet = runCatching { PacketCodec.decode(p.frame.hexToBytes()) }.getOrNull()
                ?.takeIf { it.fragment == null && it.packet.type == type }?.packet ?: return null
            return Stored(packet, at(p.at), p.hops)
        }
        entries.clear()
        for (c in saved.incidents) {
            val e = Entry(c.id8)
            e.report = stored(c.report, PacketType.INCIDENT_REPORT, maxAgeMs)
            e.ack = stored(c.ack, PacketType.INCIDENT_ACK, maxAgeMs)
            e.response.stored = stored(c.response, PacketType.INCIDENT_RESPONSE, maxAgeMs)
            e.update.stored = stored(c.update, PacketType.INCIDENT_UPDATE, maxAgeMs)
            if (e.empty && wallMs - c.touchedAt > traceMs) continue
            e.reportSeen = c.reportSeen
            e.own = c.own
            e.routine = c.routine
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
        notices.clear()
        noticesDone.clear()
        for (n in saved.notices) {
            val s = stored(n.notice, PacketType.ANNOUNCEMENT) ?: continue
            val notice = Notice.decode(s.packet.payload) ?: continue
            val order = if (n.order > 0) n.order else notice.issued
            val markUntil = if (n.markUntil > 0) n.markUntil else NoticeOrder.markUntil(order) * 1000
            if (markUntil <= wallMs) continue
            val kept = KeptNotice(notice, s.packet, s.atMs, order, nowMs + (n.until - wallMs), nowMs + (markUntil - wallMs))
            notices.getOrPut(NoticeOrder.slot(notice)) { mutableListOf() } += kept
        }
    }

    private fun purge(nowMs: Long) {
        purgedAtMs = nowMs
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val keep = if (e.routine) minOf(retentionMs, ROUTINE_MS) else retentionMs
            e.report?.let { s -> if (nowMs - s.atMs > keep) e.report = null }
            e.ack?.let { s -> if (nowMs - s.atMs > retentionMs) e.ack = null }
            for (k in listOf(e.response, e.update)) k.stored?.let { s -> if (nowMs - s.atMs > retentionMs) k.stored = null }
            if (e.empty && nowMs - e.touchedMs > traceMs) it.remove()
        }
        // an ended evacuation has nothing left to refuse; a withdrawal and a topic's latest keep their order a while
        for ((slot, list) in notices) {
            list.removeAll { k ->
                val ended = slot == NoticeOrder.EVACUATIONS && !k.notice.cancelled && nowMs >= k.untilMs
                (ended || nowMs >= k.markUntilMs).also { if (it) letGo(k.notice, nowMs) }
            }
        }
        notices.values.removeAll { it.isEmpty() }
        noticesDone.values.removeAll { it <= nowMs }
        if (entries.size > maxTraces) {
            val excess = entries.size - maxTraces
            val victims = entries.values.filter { it.empty }.sortedBy { it.touchedMs }.take(excess)
            for (v in victims) entries.remove(v.id8)
        }
    }

    companion object {
        /** How long a routine report is served: the zone's next level replaces it within minutes. */
        const val ROUTINE_MS = 5 * 60_000L

        /** A notice refused or let go isn't asked for again for this long. */
        const val NOTICE_DONE_MS = 30 * 60_000L
        private const val MAX_NOTICES_DONE = 64
    }
}
