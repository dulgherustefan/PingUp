package ro.safetyplease.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Compat10
import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentCancel
import ro.safetyplease.core.protocol.IncidentReportCodec
import ro.safetyplease.core.protocol.Limits
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Reassembler
import ro.safetyplease.core.protocol.Request2
import ro.safetyplease.core.protocol.Request2Codec
import ro.safetyplease.core.protocol.RequestCodec
import ro.safetyplease.core.protocol.RequestEntry
import ro.safetyplease.core.protocol.Summary2
import ro.safetyplease.core.protocol.Summary2Codec
import ro.safetyplease.core.protocol.SummaryCodec
import ro.safetyplease.core.protocol.SummaryEntry
import ro.safetyplease.core.protocol.SummaryKind
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.shortHex
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.util.toLong
import ro.safetyplease.core.util.truncateUtf8
import kotlin.random.Random

data class MeshConfig(
    val helloTimeoutMs: Long = 10_000,
    val connectTimeoutMs: Long = 25_000,
    val summaryIntervalMs: Long = 60_000,
    val rotationIntervalMs: Long = 60_000,
    val rotationCooldownMs: Long = 120_000,
    val seenTtlMs: Long = 30_000,
    val banMs: Long = 10 * 60_000L,
    val malformedLimit: Int = 5,
    val malformedWindowMs: Long = 60_000,
    val relayJitterMinMs: Long = 50,
    val relayJitterMaxMs: Long = 250,
    /** 20 packets per second per link. */
    val minSendSpacingMs: Long = 50,
    val rxBurst: Double = 30.0,
    val rxPerSecond: Double = 3.0,
    // a bit under the receive limit so a well-behaved node is never throttled by its neighbor
    val txBurst: Double = 28.0,
    val txPerSecond: Double = 2.8,
    val retentionMs: Long = 30 * 60_000L,
    val anchorRetentionMs: Long = 60 * 60_000L,
    /**
     * New incident reports accepted from one link in a minute. Reports are anonymous, so the limit can't follow the
     * sender; this one slows a neighbor that fills the cache with fakes, far above what people report.
     */
    val reportsPerMinute: Int = 6,
    /** Packets of types this version doesn't read, relayed from one link in a minute. */
    val opaquePerMinute: Int = 12,
    /** The same for ANNOUNCEMENT, smaller while nothing signs announcements and any phone can send one. */
    val announcementsPerMinute: Int = 4,
    /**
     * Frames, types and summary kinds this version can't use, accepted from one link in a minute. A peer may be a
     * newer version, so they never ban it; past this the link is closed.
     */
    val unsupportedPerMinute: Int = 10,
    /** Nickname is included in HELLO only in debug builds, for the demo peer list. */
    val nicknameInHello: Boolean = false,
    val allowTestPackets: Boolean = false,
)

sealed interface MeshEvent {
    class Received(val packet: Packet, val viaPeer: Long) : MeshEvent

    /** One of our packets was handed to at least one link. */
    class Sent(val packetId: Long) : MeshEvent

    class PeerLinked(val peerId: Long) : MeshEvent

    class PeerUnlinked(val peerId: Long) : MeshEvent
}

data class LinkInfo(
    val id: Int,
    val address: String,
    val peerId: Long,
    val nickname: String?,
    val flags: Int,
    val outgoing: Boolean,
    val muted: Boolean,
    val upAtMs: Long,
)

data class SeenPeer(val address: String, val prefix: Int?, val flags: Int, val rssi: Int, val lastSeenMs: Long)

enum class DropReason(val label: String) {
    DUPLICATE("duplicat"),
    KNOWN_INCIDENT("known incident"),
    STALE_ACK("stale ACK"),
    QUEUE_FULL("queue full"),
    RATE_LIMIT("rate limit"),
    MALFORMED("malformed"),
    BEFORE_HELLO("before HELLO"),
    IGNORED("ignorat"),
    TEST("test"),

    /** A frame, a packet type or a summary kind from a newer version that this one can't use. */
    UNSUPPORTED("unsupported"),
    REPORT_LIMIT("report limit"),

    /** A type this version only relays, over its size limit or over the link's budget for such types. */
    OPAQUE_LIMIT("opaque limit"),
}

/** Totals since the engine was created, for the field measurement export. */
data class MeshCounters(
    /** Every packet written to a link, by type: ours, relayed and link-local. */
    val sentByType: Map<Int, Long> = emptyMap(),
    /** Every complete packet that arrived from a link, by type, before any check. */
    val receivedByType: Map<Int, Long> = emptyMap(),
    val drops: Map<DropReason, Long> = emptyMap(),
    /** Reports and ACKs queued for a neighbor that requested them after a SUMMARY. */
    val storeAndForward: Long = 0,
    /** Links and HELLOs refused because the peer is banned. Not packets, so not in [MeshState.dropped]. */
    val refusedBanned: Long = 0,
)

data class MeshState(
    val radio: RadioStatus = RadioStatus(),
    val links: List<LinkInfo> = emptyList(),
    val seen: List<SeenPeer> = emptyList(),
    val sent: Long = 0,
    val received: Long = 0,
    val relayed: Long = 0,
    /** Packets dropped on the way in or out, the sum of [MeshCounters.drops]. */
    val dropped: Long = 0,
    /**
     * Packets lost because a link's send queue was full, refused or evicted for a more important one. Overlaps
     * [dropped]: a flooded packet the queue refuses is counted in both, as [DropReason.QUEUE_FULL]; an evicted one,
     * or a refused link-local packet or store-and-forward reply, only here.
     */
    val queueFull: Long = 0,
    val cachedReports: Int = 0,
    val counters: MeshCounters = MeshCounters(),
) {
    val readyLinks: Int get() = links.count { it.peerId != 0L && !it.muted }

    /** Phones, not addresses: a peer can briefly show up under two addresses after restarting its advertising. */
    val visiblePeers: Int get() = seen.map { it.prefix ?: it.address.hashCode() }.distinct().size
}

/**
 * Mesh core, independent of the radio: links, HELLO, dedup, TTL relay, priority queues, abuse limits
 * and store-and-forward. All state is touched from a single thread (the [scope] context).
 */
class MeshEngine(
    private val scope: CoroutineScope,
    private val radio: Radio,
    private val clock: Clock,
    private val random: Random,
    val nodeId: Long,
    private val config: MeshConfig,
    val log: PacketLog,
    private val verifyAck: (payload: ByteArray) -> Boolean,
) {
    private class Link(
        val id: Int,
        val address: String,
        val outgoing: Boolean,
        val maxFrame: Int,
        val upAtMs: Long,
        var advertisedPrefix: Int?,
        config: MeshConfig,
        onQueueFull: () -> Unit,
    ) {
        var peerId = 0L
        var peerFlags = 0
        var nickname: String? = null
        var muted = false
        var closing = false
        var lastActivityMs = upAtMs
        var summaryAtMs = upAtMs
        var nextSendAtMs = 0L
        var fragSeq = 0
        var pump: Job? = null
        val queue = SendQueue(onFull = onQueueFull)
        val reassembler = Reassembler()
        val rx = TokenBucket(config.rxBurst, config.rxPerSecond, upAtMs)
        val tx = TokenBucket(config.txBurst, config.txPerSecond, upAtMs)
        val malformedAt = ArrayDeque<Long>()
        val newReports = WindowLimit(config.reportsPerMinute, 60_000)
        val opaque = WindowLimit(config.opaquePerMinute, 60_000)
        val announcements = WindowLimit(config.announcementsPerMinute, 60_000)
        val unsupported = WindowLimit(config.unsupportedPerMinute, 60_000)

        val ready: Boolean get() = peerId != 0L && !muted && !closing
        val label: String get() = if (peerId != 0L) peerId.shortHex() else address.takeLast(5)

        /** The peer announced 1.1 in its HELLO; a 1.0 peer gets only what 1.0 reads. */
        val speaks11: Boolean get() = peerFlags and NodeFlags.PROTOCOL_1_1 != 0
    }

    private val myPrefix = nodeId.nodePrefix()
    private val links = LinkedHashMap<Int, Link>()
    private val seen = HashMap<String, Candidate>()
    private val dedup = DedupCache()
    private val cache = IncidentCache(config.retentionMs)

    /** Packets waiting out the relay jitter, with the links that already gave us a copy. */
    private val relaying = HashMap<Long, MutableSet<Int>>()

    private val addressBlockedUntil = HashMap<String, Long>()
    private val addressFailures = HashMap<String, Int>()
    private val prefixCooldownUntil = HashMap<Int, Long>()
    private val bannedNodesUntil = HashMap<Long, Long>()
    private val bannedAddressesUntil = HashMap<String, Long>()

    private var connecting: String? = null
    private var connectingSinceMs = 0L
    private var lastRotationMs = 0L
    private var ignored: Set<Int> = emptySet()
    private var radioStatus = RadioStatus()

    private var roleFlags = 0
    private var limits = LinkLimits.DEFAULT
    private var nickname: String? = null
    private var advertisedFlags = -1

    private var sentCount = 0L
    private var receivedCount = 0L
    private var relayedCount = 0L
    private var storeAndForwardCount = 0L
    private val sentByType = HashMap<Int, Long>()
    private val receivedByType = HashMap<Int, Long>()
    private val drops = HashMap<DropReason, Long>()
    private var queueFullCount = 0L
    private var refusedBannedCount = 0L

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<MeshEvent> = _events
    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state

    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return
        refreshFlags()
        jobs = listOf(
            scope.launch { for (event in radio.events) handle(event) },
            scope.launch {
                while (isActive) {
                    delay(1000)
                    tick()
                }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        links.values.forEach { it.pump?.cancel() }
        links.clear()
        seen.clear()
        connecting = null
        publishState()
    }

    // --- configuration ---

    fun setRole(staff: Boolean, anchor: Boolean) {
        val flags = (if (staff) NodeFlags.STAFF else 0) or (if (anchor) NodeFlags.ANCHOR else 0)
        if (flags == roleFlags) return
        roleFlags = flags
        limits = if (anchor) LinkLimits.ANCHOR else LinkLimits.DEFAULT
        cache.retentionMs = if (anchor) config.anchorRetentionMs else config.retentionMs
        refreshFlags()
        links.values.filter { !it.closing }.forEach { sendHello(it) }
    }

    fun setNickname(value: String?) {
        nickname = value?.truncateUtf8(Limits.NICK_BYTES)
    }

    /** Demo mode: don't connect to these prefixes and drop everything from or to them. */
    fun setIgnoredPrefixes(prefixes: Set<Int>) {
        ignored = prefixes
        for (link in links.values) {
            if (link.peerId == 0L) continue
            val mute = link.peerId.nodePrefix() in prefixes
            if (mute == link.muted) continue
            link.muted = mute
            if (!mute) {
                sendSummary(link)
                _events.tryEmit(MeshEvent.PeerLinked(link.peerId))
            }
        }
        publishState()
    }

    fun clearCaches() {
        cache.clear()
        publishState()
    }

    // --- sending ---

    fun broadcast(type: Int, payload: ByteArray, encrypted: Boolean = false, anonymous: Boolean = false): Packet {
        val packet = newPacket(type, payload, null, encrypted, anonymous)
        flood(packet, own = true)
        return packet
    }

    fun unicast(type: Int, recipient: Long, payload: ByteArray, encrypted: Boolean = true): Packet {
        val packet = newPacket(type, payload, recipient, encrypted, anonymous = false)
        flood(packet, own = true)
        return packet
    }

    /** Our own report: the cache keeps it until staff confirms receipt, whatever else arrives. */
    fun publishReport(incidentId: ByteArray, sealed: ByteArray): Packet {
        val payload = IncidentReportCodec.encode(incidentId, sealed)
        val packet = newPacket(PacketType.INCIDENT_REPORT, payload, null, encrypted = true, anonymous = true)
        cache.offerReport(incidentId, packet, clock.monoMs(), own = true)
        flood(packet, own = true)
        publishState()
        return packet
    }

    fun publishAck(ack: IncidentAck): Packet? {
        val packet = newPacket(PacketType.INCIDENT_ACK, ack.encode(), null, encrypted = false, anonymous = false)
        if (!cache.offerAck(ack, packet, clock.monoMs())) return null
        flood(packet, own = true)
        return packet
    }

    /** No store-and-forward: the author resends it, as a new packet, until the CANCELLED ACK arrives. */
    fun publishCancel(incidentId: ByteArray, token: ByteArray): Packet {
        val payload = IncidentCancel(incidentId, token).encode()
        val packet = newPacket(PacketType.INCIDENT_CANCEL, payload, null, encrypted = false, anonymous = true)
        flood(packet, own = true)
        return packet
    }

    /** After a cancel we stop spreading our own report; the trace stays so neighbors don't bring it back. */
    fun forgetReport(incidentId: ByteArray) {
        cache.forgetReport(incidentId, clock.monoMs())
        publishState()
    }

    fun ackStatus(incidentId: ByteArray): Int = cache.ackStatus(incidentId)

    fun cachedReports(): List<Pair<Packet, Int>> = cache.reports()

    fun cachedAck(incidentId: ByteArray): IncidentAck? =
        cache.ack(incidentId.toLong())?.let { IncidentAck.decode(it.payload) }

    private fun newPacket(type: Int, payload: ByteArray, recipient: Long?, encrypted: Boolean, anonymous: Boolean): Packet {
        require(payload.size <= PacketCodec.MAX_PAYLOAD) { "payload too large" }
        var id: Long
        do id = random.nextLong() while (id == 0L)
        val packet = Packet(
            type = type,
            ttl = PacketType.originTtl(type),
            id = id,
            sender = if (anonymous) 0L else nodeId,
            timestamp = clock.wallMs() / 1000,
            recipient = recipient,
            encrypted = encrypted,
            payload = payload,
        )
        dedup.add(packet.dedupKey, clock.monoMs())
        return packet
    }

    private fun flood(packet: Packet, except: Set<Int> = emptySet(), own: Boolean): Int {
        var count = 0
        for (link in links.values) {
            if (!link.ready || link.id in except || !canSend(link, packet)) continue
            if (link.queue.offer(Outbound(packet, own))) count++ else drop(packet, link, DropReason.QUEUE_FULL)
        }
        return count
    }

    /** Nothing goes to a 1.0 peer that it would count as malformed. */
    private fun canSend(link: Link, packet: Packet): Boolean = link.speaks11 || Compat10.accepts(packet)

    private fun sendLocal(link: Link, type: Int, payload: ByteArray) {
        val ttl = PacketType.originTtl(type)
        val packet = Packet(type, ttl, random.nextLong(), nodeId, clock.wallMs() / 1000, null, false, payload)
        link.queue.offer(Outbound(packet, own = true))
    }

    private fun sendHello(link: Link) {
        val nick = if (config.nicknameInHello) nickname else null
        sendLocal(link, PacketType.HELLO, Hello(PacketCodec.VERSION, nodeId, currentFlags(), nick).encode())
    }

    /** SUMMARY2 to a 1.1 peer, the 1.0 SUMMARY to the others. */
    private fun sendSummary(link: Link) {
        val now = clock.monoMs()
        link.summaryAtMs = now
        val entries = cache.summary(now) { canSend(link, it) }
        if (entries.isEmpty()) return
        if (link.speaks11) {
            sendLocal(link, PacketType.SUMMARY2, Summary2Codec.encode(Summary2(SummaryKind.INCIDENTS, Summary2Codec.fitting(entries))))
        } else {
            sendLocal(link, PacketType.SUMMARY, SummaryCodec.encode(entries))
        }
    }

    private fun startPump(link: Link) {
        link.pump = scope.launch {
            while (isActive) {
                link.queue.awaitItem()
                val now = clock.monoMs()
                val wait = maxOf(link.nextSendAtMs - now, link.tx.waitMs(now))
                if (wait > 0) delay(wait)
                // pick only after the wait, so an incident that arrived meanwhile jumps the queue
                val out = link.queue.poll() ?: continue
                link.tx.tryTake(clock.monoMs())
                link.nextSendAtMs = clock.monoMs() + config.minSendSpacingMs
                val frames = PacketCodec.toFrames(out.packet, link.maxFrame, link.fragSeq++)
                for (frame in frames) {
                    if (!radio.send(link.id, frame)) {
                        log.link(clock.wallMs(), link.label, "send failed")
                        close(link)
                        return@launch
                    }
                }
                onWritten(link, out)
            }
        }
    }

    private fun onWritten(link: Link, out: Outbound) {
        val packet = out.packet
        log.packet(clock.wallMs(), if (out.own) LogKind.TX else LogKind.RELAY, packet, link.label)
        sentByType.bump(packet.type)
        if (PacketType.isLinkLocal(packet.type)) return
        link.lastActivityMs = clock.monoMs()
        if (out.own) {
            sentCount++
            _events.tryEmit(MeshEvent.Sent(packet.id))
        }
    }

    // --- radio events ---

    private fun handle(event: RadioEvent) {
        when (event) {
            is RadioEvent.PeerSeen -> onPeerSeen(event)
            is RadioEvent.LinkUp -> onLinkUp(event)
            is RadioEvent.LinkDown -> onLinkDown(event.link)
            is RadioEvent.Frame -> onFrame(event.link, event.bytes)
            is RadioEvent.ConnectFailed -> onConnectFailed(event.address)
            is RadioEvent.Status -> {
                // With the radio off everything seen is stale: after a restart, wait for fresh advertising.
                if (radioStatus.bluetoothOn && !event.status.bluetoothOn) {
                    seen.clear()
                    connecting = null
                }
                radioStatus = event.status
                publishState()
            }
        }
    }

    private fun onPeerSeen(e: RadioEvent.PeerSeen) {
        val now = clock.monoMs()
        val old = seen[e.address]
        seen[e.address] = Candidate(e.address, e.prefix ?: old?.prefix, e.flags, e.rssi, old?.sinceMs ?: now, now)
        maintainConnections(now)
    }

    private fun onLinkUp(e: RadioEvent.LinkUp) {
        val now = clock.monoMs()
        if (e.outgoing && connecting == e.address) connecting = null
        val banned = (bannedAddressesUntil[e.address] ?: 0) > now
        val overLimit = !e.outgoing && links.values.count { !it.outgoing && !it.closing } >= limits.maxIn
        if (banned || overLimit || e.maxFrame < PacketCodec.MIN_FRAME) {
            if (banned) refusedBannedCount++
            log.link(clock.wallMs(), e.address.takeLast(5), "refuzat")
            radio.disconnect(e.link)
            return
        }
        val link = Link(e.link, e.address, e.outgoing, e.maxFrame, now, seen[e.address]?.prefix, config) { queueFullCount++ }
        links[e.link] = link
        log.link(clock.wallMs(), link.label, if (e.outgoing) "link up (out) mtu=${e.maxFrame}" else "link up (in) mtu=${e.maxFrame}")
        startPump(link)
        sendHello(link)
        refreshFlags()
        publishState()
    }

    private fun onLinkDown(id: Int) {
        val link = links.remove(id) ?: return
        link.pump?.cancel()
        val now = clock.monoMs()
        log.link(clock.wallMs(), link.label, "link down")
        // a link that drops before HELLO (refused by the peer, say) isn't retried right away
        if (link.outgoing && link.peerId == 0L) registerFailure(link.address)
        // After a lost link, forget the peer: if it's still in range we'll see its advertising within a second;
        // if it left, we won't keep connecting to its last address.
        val prefix = if (link.peerId != 0L) link.peerId.nodePrefix() else link.advertisedPrefix
        seen.entries.removeAll { (address, c) -> address == link.address || (prefix != null && c.prefix == prefix) }
        if (link.peerId != 0L && links.values.none { it.peerId == link.peerId && it.ready }) {
            _events.tryEmit(MeshEvent.PeerUnlinked(link.peerId))
        }
        refreshFlags()
        publishState()
    }

    private fun onConnectFailed(address: String) {
        if (connecting == address) connecting = null
        registerFailure(address)
    }

    private fun registerFailure(address: String) {
        val failures = (addressFailures[address] ?: 0) + 1
        addressFailures[address] = failures
        val base = minOf(5_000L shl minOf(failures - 1, 4), 60_000L)
        // Two nodes failing toward the same peer at once would retry together and collide again,
        // so the wait is random between 0.5x and 1.5x the base, with the same mean.
        val backoff = base / 2 + random.nextLong(base)
        addressBlockedUntil[address] = clock.monoMs() + backoff
        log.link(clock.wallMs(), address.takeLast(5), "connect failed, retry in ${backoff / 100 / 10.0}s")
    }

    private fun onFrame(linkId: Int, bytes: ByteArray) {
        val link = links[linkId] ?: return
        if (link.closing) return
        val now = clock.monoMs()
        val frame = PacketCodec.decode(bytes)
            ?: return if (PacketCodec.isForeign(bytes)) unsupported(link) else malformed(link, "invalid frame")
        val packet = when (val result = link.reassembler.accept(frame, now)) {
            is Reassembler.Result.Complete -> result.packet
            Reassembler.Result.Pending -> return
            Reassembler.Result.Invalid -> return malformed(link, "invalid fragments")
        }
        receivedByType.bump(packet.type)
        // a copy we already have costs no receive token
        if (isDuplicate(link, packet, now)) return drop(packet, link, DropReason.DUPLICATE)
        if (!link.rx.tryTake(now)) return drop(packet, link, DropReason.RATE_LIMIT)
        if (packet.type == PacketType.TEST && !config.allowTestPackets) return drop(packet, link, DropReason.TEST)

        if (packet.type == PacketType.HELLO) return onHello(link, packet)
        if (link.peerId == 0L) return drop(packet, link, DropReason.BEFORE_HELLO)
        if (link.muted) return drop(packet, link, DropReason.IGNORED)

        when (packet.type) {
            PacketType.SUMMARY, PacketType.SUMMARY2 -> onSummary(link, packet)
            PacketType.REQUEST, PacketType.REQUEST2 -> onRequest(link, packet)
            else -> if (PacketType.isKnown(packet.type)) onMeshPacket(link, packet, now) else onOpaque(link, packet, now)
        }
    }

    /** The link that sent it already has it, so a pending relay skips that link. */
    private fun isDuplicate(link: Link, packet: Packet, now: Long): Boolean {
        if (PacketType.isLinkLocal(packet.type) || !dedup.contains(packet.dedupKey, now)) return false
        relaying[packet.dedupKey]?.add(link.id)
        return true
    }

    private fun onHello(link: Link, packet: Packet) {
        val hello = Hello.decode(packet.payload)
        if (hello == null || hello.version < PacketCodec.VERSION || hello.nodeId != packet.sender) {
            return malformed(link, "HELLO invalid")
        }
        val now = clock.monoMs()
        val banned = (bannedNodesUntil[hello.nodeId] ?: 0) > now
        if (hello.nodeId == nodeId || banned) {
            if (banned) refusedBannedCount++
            close(link)
            return
        }
        if (link.peerId != 0L) {
            if (link.peerId != hello.nodeId) return malformed(link, "HELLO with a different nodeId")
            link.peerFlags = hello.flags
            link.nickname = hello.nickname
            publishState()
            return
        }
        log.packet(clock.wallMs(), LogKind.RX, packet, hello.nodeId.shortHex())
        val twin = links.values.firstOrNull { it !== link && it.peerId == hello.nodeId && !it.closing }
        if (twin != null) {
            val loser = duplicateLoser(older = twin, newer = link, peerId = hello.nodeId)
            log.link(clock.wallMs(), hello.nodeId.shortHex(), "duplicate link, closing the ${if (loser === link) "new" else "old"} one")
            close(loser)
            if (loser === link) return
        }
        link.peerId = hello.nodeId
        link.peerFlags = hello.flags
        link.nickname = hello.nickname
        addressFailures.remove(link.address)
        link.muted = hello.nodeId.nodePrefix() in ignored
        if (!link.muted) {
            sendSummary(link)
            _events.tryEmit(MeshEvent.PeerLinked(link.peerId))
        }
        publishState()
    }

    /** Both ends must pick the same link without talking: keep the one initiated by the lower nodeId. */
    private fun duplicateLoser(older: Link, newer: Link, peerId: Long): Link {
        val preferOutgoing = java.lang.Long.compareUnsigned(nodeId, peerId) < 0
        val olderPreferred = older.outgoing == preferOutgoing
        val newerPreferred = newer.outgoing == preferOutgoing
        return if (olderPreferred && !newerPreferred) newer else older
    }

    /** A SUMMARY is answered with a REQUEST, a SUMMARY2 with a REQUEST2 of the same kind. */
    private fun onSummary(link: Link, packet: Packet) {
        val entries: List<SummaryEntry> = if (packet.type == PacketType.SUMMARY) {
            SummaryCodec.decode(packet.payload) ?: return malformed(link, "invalid SUMMARY")
        } else {
            val summary = Summary2Codec.decode(packet.payload) ?: return malformed(link, "invalid SUMMARY2")
            if (summary.kind != SummaryKind.INCIDENTS) return drop(packet, link, DropReason.UNSUPPORTED)
            summary.entries
        }
        log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "${entries.size} entries")
        val now = clock.monoMs()
        val wanted = cache.missing(entries, now).withinReportBudget(link.newReports.remaining(now))
        if (wanted.isNotEmpty()) sendRequest(link, packet.type, wanted)
    }

    /** Reports past the link's budget for new ones would be dropped on arrival, so they wait for a later summary. */
    private fun List<RequestEntry>.withinReportBudget(budget: Int): List<RequestEntry> {
        var left = budget
        return mapNotNull { e ->
            when {
                !e.wantReport -> e
                left > 0 -> e.also { left-- }
                e.wantAck -> e.copy(wantReport = false)
                else -> null
            }
        }
    }

    private fun sendRequest(link: Link, summaryType: Int, wanted: List<RequestEntry>) {
        if (summaryType == PacketType.SUMMARY) {
            sendLocal(link, PacketType.REQUEST, RequestCodec.encode(wanted))
        } else {
            sendLocal(link, PacketType.REQUEST2, Request2Codec.encode(Request2(SummaryKind.INCIDENTS, wanted)))
        }
    }

    private fun onRequest(link: Link, packet: Packet) {
        val entries: List<RequestEntry> = if (packet.type == PacketType.REQUEST) {
            RequestCodec.decode(packet.payload) ?: return malformed(link, "invalid REQUEST")
        } else {
            val request = Request2Codec.decode(packet.payload) ?: return malformed(link, "invalid REQUEST2")
            if (request.kind != SummaryKind.INCIDENTS) return drop(packet, link, DropReason.UNSUPPORTED)
            request.entries
        }
        log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "${entries.size} entries")
        for (e in entries) {
            if (e.wantReport) cache.report(e.id8)?.let { serve(link, it) }
            if (e.wantAck) cache.ack(e.id8)?.let { serve(link, it) }
        }
    }

    private fun serve(link: Link, packet: Packet) {
        if (canSend(link, packet) && link.queue.offer(Outbound(packet, own = false))) storeAndForwardCount++
    }

    private fun onMeshPacket(link: Link, packet: Packet, now: Long) {
        // before dedup, so the same report can still come later, through this link or another
        if (isReportOverLimit(link, packet, now)) return drop(packet, link, DropReason.REPORT_LIMIT)
        dedup.add(packet.dedupKey, now)
        // what we keep for store-and-forward is relayed with one hop less
        val forwardable = packet.withTtl(maxOf(packet.ttl - 1, 1))
        when (packet.type) {
            PacketType.INCIDENT_REPORT -> {
                if (!IncidentReportCodec.isWellFormed(packet.payload)) return malformed(link, "invalid report")
                val incidentId = IncidentReportCodec.incidentId(packet.payload)
                if (!cache.offerReport(incidentId, forwardable, now, packet.hops)) return drop(packet, link, DropReason.KNOWN_INCIDENT)
                link.newReports.tryAdd(now)
            }
            PacketType.INCIDENT_ACK -> {
                val ack = IncidentAck.decode(packet.payload) ?: return malformed(link, "ACK invalid")
                if (!verifyAck(packet.payload)) return malformed(link, "ACK with invalid signature")
                // a status from a newer version has no place in the supersede order: relayed, neither kept nor delivered
                if (!AckStatus.isKnown(ack.status)) return relayOnward(link, packet)
                if (!cache.offerAck(ack, forwardable, now)) return drop(packet, link, DropReason.STALE_ACK)
            }
            PacketType.INCIDENT_CANCEL -> {
                if (packet.payload.size < IncidentCancel.SIZE) return malformed(link, "invalid CANCEL")
            }
            PacketType.PRIVATE -> {
                if (packet.recipient == null || packet.sender == 0L || !packet.encrypted) {
                    return malformed(link, "invalid PRIVATE")
                }
            }
        }
        link.lastActivityMs = now
        val mine = packet.recipient == nodeId
        if (packet.recipient == null || mine) {
            receivedCount++
            log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "hops=${packet.hops}")
            _events.tryEmit(MeshEvent.Received(packet, link.peerId))
        }
        relayOnward(link, packet)
        publishState()
    }

    /** Only a report that would be stored counts against the limit; copies and known incidents don't. */
    private fun isReportOverLimit(link: Link, packet: Packet, now: Long): Boolean =
        packet.type == PacketType.INCIDENT_REPORT && IncidentReportCodec.isWellFormed(packet.payload) &&
            !cache.hasSeenReport(IncidentReportCodec.incidentId(packet.payload), now) && link.newReports.isFull(now)

    /** A type from a newer version: relayed as it is, but not far, not large and not often, and never to a 1.0 peer. */
    private fun onOpaque(link: Link, packet: Packet, now: Long) {
        if (PacketType.isLinkLocal(packet.type) || packet.recipient == nodeId || packet.ttl <= 1) {
            return drop(packet, link, DropReason.UNSUPPORTED)
        }
        val budget = if (packet.type == PacketType.ANNOUNCEMENT) link.announcements else link.opaque
        if (packet.payload.size > PacketType.opaqueMaxPayload(packet.type) || !budget.tryAdd(now)) {
            return drop(packet, link, DropReason.OPAQUE_LIMIT)
        }
        dedup.add(packet.dedupKey, now)
        relay(packet.withTtl(minOf(packet.ttl - 1, PacketType.OPAQUE_MAX_TTL)), link)
        publishState()
    }

    private fun relayOnward(from: Link, packet: Packet) {
        if (packet.recipient != nodeId && packet.ttl > 1) relay(packet.withTtl(packet.ttl - 1), from)
    }

    private fun relay(packet: Packet, from: Link) {
        relayedCount++
        val jitter = random.nextLong(config.relayJitterMinMs, config.relayJitterMaxMs + 1)
        val key = packet.dedupKey
        val delivered = mutableSetOf(from.id)
        relaying[key] = delivered
        scope.launch {
            delay(jitter)
            relaying.remove(key)
            flood(packet, except = delivered, own = false)
        }
    }

    private fun drop(packet: Packet, link: Link, reason: DropReason) {
        drops.bump(reason)
        log.packet(clock.wallMs(), LogKind.DROP, packet, link.label, reason.label)
        if (reason == DropReason.UNSUPPORTED) countUnsupported(link)
    }

    private fun unsupported(link: Link) {
        drops.bump(DropReason.UNSUPPORTED)
        log.link(clock.wallMs(), link.label, "frame from a newer version")
        countUnsupported(link)
    }

    /**
     * A link that brings mostly what this version can't use is closed, without a ban, and this node waits as after a
     * rotation before connecting to that peer again; the peer may still connect to it.
     */
    private fun countUnsupported(link: Link) {
        val now = clock.monoMs()
        if (link.unsupported.tryAdd(now)) return
        log.link(clock.wallMs(), link.label, "too much from a newer version, closing")
        val prefix = if (link.peerId != 0L) link.peerId.nodePrefix() else link.advertisedPrefix
        prefix?.let { prefixCooldownUntil[it] = now + config.rotationCooldownMs }
        close(link)
    }

    private fun malformed(link: Link, reason: String) {
        drops.bump(DropReason.MALFORMED)
        val now = clock.monoMs()
        log.link(clock.wallMs(), link.label, "malformed: $reason")
        link.malformedAt.addLast(now)
        while (link.malformedAt.isNotEmpty() && now - link.malformedAt.first() > config.malformedWindowMs) {
            link.malformedAt.removeFirst()
        }
        if (link.malformedAt.size < config.malformedLimit) return
        if (link.peerId != 0L) bannedNodesUntil[link.peerId] = now + config.banMs
        bannedAddressesUntil[link.address] = now + config.banMs
        log.link(clock.wallMs(), link.label, "ignored for ${config.banMs / 60_000} min")
        close(link)
    }

    private fun close(link: Link) {
        if (link.closing) return
        link.closing = true
        link.pump?.cancel()
        radio.disconnect(link.id)
    }

    // --- periodic maintenance ---

    private fun tick() {
        val now = clock.monoMs()
        seen.values.removeAll { now - it.lastSeenMs > config.seenTtlMs }
        addressBlockedUntil.values.removeAll { it <= now }
        prefixCooldownUntil.values.removeAll { it <= now }
        bannedNodesUntil.values.removeAll { it <= now }
        bannedAddressesUntil.values.removeAll { it <= now }

        for (link in links.values.toList()) {
            if (link.closing) continue
            if (link.peerId == 0L && now - link.upAtMs > config.helloTimeoutMs) {
                log.link(clock.wallMs(), link.label, "no HELLO")
                close(link)
            } else if (link.ready && now - link.summaryAtMs >= config.summaryIntervalMs) {
                sendSummary(link)
            }
        }

        if (connecting != null && now - connectingSinceMs > config.connectTimeoutMs) {
            onConnectFailed(connecting!!)
        }
        if (now - lastRotationMs >= config.rotationIntervalMs) {
            lastRotationMs = now
            rotate(now)
        }
        maintainConnections(now)
        publishState()
    }

    private fun isBlocked(c: Candidate, now: Long): Boolean {
        if ((addressBlockedUntil[c.address] ?: 0) > now || (bannedAddressesUntil[c.address] ?: 0) > now) return true
        val prefix = c.prefix ?: return false
        return prefix in ignored || (prefixCooldownUntil[prefix] ?: 0) > now ||
            bannedNodesUntil.keys.any { it.nodePrefix() == prefix }
    }

    private fun linkViews(): List<LinkView> = links.values.map {
        val prefix = if (it.peerId != 0L) it.peerId.nodePrefix() else it.advertisedPrefix
        LinkView(it.id, it.address, prefix, it.peerFlags, it.outgoing, it.upAtMs, it.lastActivityMs)
    }

    private fun maintainConnections(now: Long) {
        if (connecting != null || !radioStatus.bluetoothOn) return
        val pick = ConnectionPolicy.pick(
            myPrefix, radioStatus.canAdvertise, limits, seen.values, linkViews(), now,
        ) { isBlocked(it, now) } ?: return
        connecting = pick.address
        connectingSinceMs = now
        log.link(clock.wallMs(), pick.address.takeLast(5), "connect ${pick.prefix?.toHex() ?: "?"} rssi=${pick.rssi}")
        radio.connect(pick.address)
    }

    private fun rotate(now: Long) {
        val victim = ConnectionPolicy.rotationVictim(
            myPrefix, radioStatus.canAdvertise, limits, seen.values, linkViews(), now,
        ) { isBlocked(it, now) } ?: return
        val link = links[victim.id] ?: return
        victim.peerPrefix?.let { prefixCooldownUntil[it] = now + config.rotationCooldownMs }
        log.link(clock.wallMs(), link.label, "rotatie")
        close(link)
    }

    /**
     * What any scanner nearby sees and what HELLO tells a neighbor. A pending report stays out of both,
     * so the reporter isn't a beacon.
     */
    private fun currentFlags(): Int {
        var flags = roleFlags or NodeFlags.PROTOCOL_1_1
        if (links.values.count { !it.outgoing && !it.closing } < limits.maxIn) {
            flags = flags or NodeFlags.ACCEPTS_CONNECTIONS
        }
        return flags
    }

    private fun refreshFlags() {
        val flags = currentFlags()
        if (flags == advertisedFlags) return
        advertisedFlags = flags
        radio.setAdvertisedFlags(flags)
    }

    private fun publishState() {
        _state.value = MeshState(
            radio = radioStatus,
            links = links.values.filter { !it.closing }.map {
                LinkInfo(it.id, it.address, it.peerId, it.nickname, it.peerFlags, it.outgoing, it.muted, it.upAtMs)
            },
            seen = seen.values.map { SeenPeer(it.address, it.prefix, it.flags, it.rssi, it.lastSeenMs) }
                .sortedByDescending { it.rssi },
            sent = sentCount,
            received = receivedCount,
            relayed = relayedCount,
            dropped = drops.values.sum(),
            queueFull = queueFullCount,
            cachedReports = cache.reportCount,
            counters = MeshCounters(
                sentByType.toMap(), receivedByType.toMap(), drops.toMap(), storeAndForwardCount, refusedBannedCount,
            ),
        )
    }

    private fun <K> HashMap<K, Long>.bump(key: K) {
        this[key] = (this[key] ?: 0L) + 1
    }
}
