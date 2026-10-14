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
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.crypto.ReservedNames
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Compat10
import ro.safetyplease.core.protocol.CompatAuth
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentCancel
import ro.safetyplease.core.protocol.IncidentReportCodec
import ro.safetyplease.core.protocol.IncidentResponse
import ro.safetyplease.core.protocol.IncidentUpdateCodec
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
    /** The radio's time to set up a link; the engine gives up [MeshEngine.CONNECT_GRACE_MS] later if the radio never answers. */
    val connectTimeoutMs: Long = 20_000,
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
    /** New reporter updates accepted from one link in a minute, counted apart from reports and for the same reason. */
    val updatesPerMinute: Int = 6,
    /** Packets of types this version doesn't read, relayed from one link in a minute. */
    val opaquePerMinute: Int = 12,
    /** The same for ANNOUNCEMENT, smaller: any phone can send one, and a signed one can be sent again under new ids. */
    val announcementsPerMinute: Int = 4,
    /**
     * Frames, types and summary kinds this version can't use, accepted from one link in a minute. A peer may be a
     * newer version, so they never ban it; past this the link is closed.
     */
    val unsupportedPerMinute: Int = 10,
    /** Nickname is included in HELLO only in debug builds, for the demo peer list. */
    val nicknameInHello: Boolean = false,
    val allowTestPackets: Boolean = false,
    /** A relay passes chat on only some of its links ([FanOut]) instead of all of them. */
    val chatFanOut: Boolean = MeshEngine.CHAT_FAN_OUT,
    /** A participant that had a link with staff or an anchor lately becomes a leaf ([LinkMode.LEAF]). */
    val leaves: Boolean = MeshEngine.LEAVES,
    /**
     * Experiment, off: a relay passes on what its cache takes as new (reports, ACKs, responses, updates, notices) even
     * with the TTL spent. The cache already stops a second relay of the same one, so the TTL only slowed them.
     */
    val cachedPastTtl: Boolean = false,
    /** Experiment, off: a copy that comes from a link takes the same packet out of that link's send queue. */
    val dropQueuedCopies: Boolean = false,
    /**
     * Experiment, off: a PRIVATE goes only on the link the recipient's own packets last came from while that route is
     * fresh ([routeFreshMs]), and floods where there is none.
     */
    val routedUnicast: Boolean = false,
    /** Experiment, off: a copy with more TTL left that comes during the relay delay raises the TTL relayed. */
    val bestTtl: Boolean = false,
    /** How long a route learned from a phone's packet is followed ([routedUnicast]). */
    val routeFreshMs: Long = MeshEngine.ROUTE_FRESH_MS,
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
    val upAtMs: Long,
    /** The prefix the peer advertises, as in [SeenPeer.prefix]; null while unknown. */
    val prefix: Int? = null,
    /** The STAFF and ANCHOR bits of [flags] are proven by a certificate (PROTOCOL.md, section 6.1). */
    val verified: Boolean = false,
)

data class SeenPeer(val address: String, val prefix: Int?, val flags: Int, val rssi: Int, val lastSeenMs: Long)

enum class DropReason(val label: String) {
    DUPLICATE("duplicat"),
    KNOWN_INCIDENT("known incident"),

    /** An ACK, a response or a notice that doesn't supersede the one kept, or a notice that expired. */
    STALE_ACK("stale ACK"),
    QUEUE_FULL("queue full"),
    RATE_LIMIT("rate limit"),
    MALFORMED("malformed"),
    BEFORE_HELLO("before HELLO"),
    TEST("test"),

    /** A frame, a packet type or a summary kind from a newer version that this one can't use. */
    UNSUPPORTED("unsupported"),
    REPORT_LIMIT("report limit"),

    /** A type this version only relays, over its size limit or over the link's budget for such types. */
    OPAQUE_LIMIT("opaque limit"),

    /**
     * An ACK or a response whose certificate this phone doesn't trust now: another event, revoked, out of its shift,
     * not its role; or an announcement whose signature doesn't verify.
     */
    UNTRUSTED("untrusted"),

    /** A new update over the link's budget for them; like a report over its limit, it may still come later. */
    UPDATE_LIMIT("update limit"),
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
    /** Links refused because the address they came from is banned. Not packets, so not in [MeshState.dropped]. */
    val refusedBanned: Long = 0,
    /** New reports and ACKs this node accepted, by the hops they traveled. */
    val reportHops: Map<Int, Long> = emptyMap(),
    val ackHops: Map<Int, Long> = emptyMap(),
    /** Links the radio brought up that this node kept, and links that went down. */
    val linksOpened: Long = 0,
    val linksClosed: Long = 0,
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
    val readyLinks: Int get() = links.count { it.peerId != 0L }

    /** Phones, not addresses: a peer can briefly show up under two addresses after restarting its advertising. */
    val visiblePeers: Int get() = seen.map { it.prefix ?: it.address.hashCode() }.distinct().size
}

/**
 * Mesh core, independent of the radio: links, HELLO, dedup, TTL relay, priority queues, abuse limits
 * and store-and-forward. All state is touched from a single thread (the [scope] context).
 * With a [cacheStore], the reports and ACKs kept for store-and-forward outlive the process.
 */
class MeshEngine(
    private val scope: CoroutineScope,
    private val radio: Radio,
    private val clock: Clock,
    private val random: Random,
    val nodeId: Long,
    private val config: MeshConfig,
    val log: PacketLog,
    private val cacheStore: JsonStore<CachedIncidents>? = null,
    private val trust: MeshTrust,
) {
    private class Link(
        val id: Int,
        val address: String,
        val outgoing: Boolean,
        val maxFrame: Int,
        val upAtMs: Long,
        /** What we saw advertised at the address we connected to; null on links the peer opened. */
        val advertisedPrefix: Int?,
        config: MeshConfig,
        /** Our challenge to the peer in HELLO, the same for the whole link. */
        val nonce: ByteArray,
        onQueueFull: () -> Unit,
    ) {
        var peerId = 0L
        var peerFlags = 0
        var helloPrefix: Int? = null
        var nickname: String? = null
        var closing = false
        var lastActivityMs = upAtMs

        /** When the peer last sent a packet that isn't link-local; null if it never did. */
        var lastInboundMs: Long? = null

        /** When the link last carried anything but HELLO, either way; null before the peer's HELLO. */
        var syncMs: Long? = null

        /** Taken on the slot a phone keeps for an anchor ([NodeFlags.ANCHOR_SLOT]): closed if HELLO isn't an anchor's. */
        var spare = false

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
        val newUpdates = WindowLimit(config.updatesPerMinute, 60_000)
        val opaque = WindowLimit(config.opaquePerMinute, 60_000)
        val announcements = WindowLimit(config.announcementsPerMinute, 60_000)
        val unsupported = WindowLimit(config.unsupportedPerMinute, 60_000)

        /** The peer's challenge, and the roles its answer to ours proved (NodeFlags bits). */
        var peerNonce: ByteArray? = null
        var provenRoles = 0

        val ready: Boolean get() = peerId != 0L && !closing
        val label: String get() = if (peerId != 0L) peerId.shortHex() else address.takeLast(5)

        /** The peer announced 1.1 in its HELLO; a 1.0 peer gets only what 1.0 reads. */
        val speaks11: Boolean get() = peerFlags and NodeFlags.PROTOCOL_1_1 != 0

        val readsPrefix: Boolean get() = peerFlags and NodeFlags.PREFIX_IN_HELLO != 0

        val readsAuth: Boolean get() = peerFlags and NodeFlags.HELLO_AUTH != 0

        /** The peer's flags with STAFF only if proven ([ProvenRoles]). */
        val roleFlags: Int get() = ProvenRoles.linkFlags(peerFlags, provenRoles)

        val verified: Boolean get() = ProvenRoles.verified(peerFlags, provenRoles)

        /** What the peer advertises: from its HELLO; a peer that doesn't rotate advertises its nodeId's prefix. */
        val peerPrefix: Int?
            get() = helloPrefix ?: if (peerId != 0L && !readsPrefix) peerId.nodePrefix() else advertisedPrefix
    }

    private val rotatingPrefix = AdvertisedPrefix(random, clock.monoMs())
    private val links = LinkedHashMap<Int, Link>()
    private val seen = HashMap<String, Candidate>()
    private val dedup = DedupCache()
    private val positionBudget = PositionBudget(config.txPerSecond)
    private val cache = IncidentCache(config.retentionMs).apply {
        cacheStore?.let { store ->
            // only a cache: a file it can't read is deleted, so that saving starts over instead of stopping for good
            if (store.unreadable) store.reset()
            else restore(store.value, clock.monoMs(), clock.wallMs(), maxOf(config.retentionMs, config.anchorRetentionMs))
        }
    }

    /** A packet waiting out the relay jitter: the links that already gave us a copy, and the TTL it will go with. */
    private class Pending(val delivered: MutableSet<Int>, var ttl: Int, val maxTtl: Int)

    private val relaying = HashMap<Long, Pending>()

    /** With [MeshConfig.routedUnicast]: by nodeId, the link its last packet came from, its hops then, and when. */
    private class Route(val link: Int, val hops: Int, val atMs: Long)

    private val routes = LinkedHashMap<Long, Route>()

    private val addressBlockedUntil = HashMap<String, Long>()
    private val addressFailures = HashMap<String, Int>()
    private val prefixCooldownUntil = HashMap<Int, Long>()
    private val bannedAddressesUntil = HashMap<String, Long>()

    /** Prefixes whose last link claimed a role it didn't prove: their advertised role is ignored for a while. */
    private val unprovenUntil = HashMap<Int, Long>()

    /**
     * Addresses whose link closed as a twin of another link with the same peer: the nodeId found there. An iPhone, and
     * an Android phone to an iPhone in the background, advertises under another address than it connects from and its
     * prefix isn't read, so going back to that address would only open the same twin again. Kept while the peer is linked.
     */
    private val twinAddresses = HashMap<String, Long>()

    /** Demo mode: where an ignored peer was found, kept away from until its address and prefix change. */
    private val shutOutAddressesUntil = HashMap<String, Long>()
    private val shutOutPrefixesUntil = HashMap<Int, Long>()

    private var connecting: String? = null
    private var connectingSinceMs = 0L
    private var connectingTimeoutMs = 0L
    private var lastRotationMs = 0L
    private var ignored: Set<Int> = emptySet()
    private var radioStatus = RadioStatus()

    private var roleFlags = 0
    private var mode = LinkMode.RELAY

    /** When this phone last had a link with staff or an anchor; it is a leaf for a while after. */
    private var roleLinkMs: Long? = null

    /** When the last session with each peer ended, by the prefix the peer advertised. */
    private val sessionEnds = HashMap<Int, Long>()

    /** When this phone last wrote a report, an ACK or a cancel. */
    private var ownNewsMs: Long? = null

    /** An anchor stops taking connections for a random while after each one it takes. */
    private var admitPausedUntilMs = 0L
    private var nickname: String? = null
    private var advertisedFlags = -1
    private var advertisedPrefix: Int? = null

    private var sentCount = 0L
    private var receivedCount = 0L
    private var relayedCount = 0L
    private var storeAndForwardCount = 0L
    private val sentByType = HashMap<Int, Long>()
    private val receivedByType = HashMap<Int, Long>()
    private val drops = HashMap<DropReason, Long>()
    private var queueFullCount = 0L
    private var refusedBannedCount = 0L
    private val reportHops = HashMap<Int, Long>()
    private val ackHops = HashMap<Int, Long>()
    private var linksOpened = 0L
    private var linksClosed = 0L

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1024)
    val events: SharedFlow<MeshEvent> = _events
    private val _state = MutableStateFlow(MeshState())
    val state: StateFlow<MeshState> = _state

    private var jobs: List<Job> = emptyList()

    fun start() {
        if (jobs.isNotEmpty()) return
        refreshAdvertising()
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
        twinAddresses.clear()
        connecting = null
        publishState()
    }

    // --- configuration ---

    fun setRole(staff: Boolean, anchor: Boolean) {
        val flags = (if (staff) NodeFlags.STAFF else 0) or (if (anchor) NodeFlags.ANCHOR else 0)
        if (flags == roleFlags) return
        roleFlags = flags
        updateMode(clock.monoMs())
        cache.retentionMs = if (anchor) config.anchorRetentionMs else config.retentionMs
        // the HELLOs go first, so neighbors know a changed prefix before they see it advertised
        links.values.filter { !it.closing }.forEach { sendHello(it) }
        refreshAdvertising()
    }

    fun setNickname(value: String?) {
        nickname = value?.truncateUtf8(Limits.NICK_BYTES)
    }

    /**
     * Demo mode: no link with the phones whose nodeId has one of these prefixes. A rotating prefix doesn't show who
     * advertises it, so such a link opens and closes as soon as HELLO tells; an anchor advertises its nodeId's prefix
     * and is never connected to.
     */
    fun setIgnoredPrefixes(prefixes: Set<Int>) {
        ignored = prefixes
        shutOutAddressesUntil.clear()
        shutOutPrefixesUntil.clear()
        val now = clock.monoMs()
        for (link in links.values.toList()) if (!link.closing && link.peerId != 0L && isIgnored(link.peerId)) shutOut(link, now)
        publishState()
    }

    private fun isIgnored(peerId: Long): Boolean = peerId.nodePrefix() in ignored

    /** Closes an ignored peer's link and keeps away from its address and prefix until it advertises new ones. */
    private fun shutOut(link: Link, now: Long) {
        val until = now + AdvertisedPrefix.INTERVAL_MS
        shutOutAddressesUntil[link.address] = until
        link.peerPrefix?.let { shutOutPrefixesUntil[it] = until }
        log.link(clock.wallMs(), link.label, "ignored")
        close(link)
    }

    fun clearCaches() {
        cache.clear()
        saveCache()
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

    /** A shared position, a PRIVATE of class P2 ([PositionBudget]): null when the budget has no room for it now. */
    fun unicastPosition(recipient: Long, payload: ByteArray): Packet? {
        if (!positionBudget.tryPosition(clock.monoMs())) return null
        val packet = newPacket(PacketType.PRIVATE, payload, recipient, encrypted = true, anonymous = false)
        flood(packet, own = true, position = true)
        return packet
    }

    /**
     * Our own report: the cache keeps it until staff confirms receipt, whatever else arrives; a [routine] one, which
     * nobody confirms ([IncidentCache.settle]), only briefly.
     */
    fun publishReport(incidentId: ByteArray, sealed: ByteArray, routine: Boolean = false): Packet {
        val payload = IncidentReportCodec.encode(incidentId, sealed)
        val packet = newPacket(PacketType.INCIDENT_REPORT, payload, null, encrypted = true, anonymous = true)
        val kept = cache.offerReport(incidentId, packet, clock.monoMs(), own = true)
        if (routine) cache.settle(incidentId, clock.monoMs())
        if (kept || routine) saveCache()
        ownNewsMs = clock.monoMs()
        flood(packet, own = true)
        publishState()
        return packet
    }

    fun publishAck(ack: IncidentAck): Packet? {
        val packet = newPacket(PacketType.INCIDENT_ACK, ack.encode(), null, encrypted = false, anonymous = false)
        if (!cache.offerAck(ack, packet, clock.monoMs())) return null
        saveCache()
        ownNewsMs = clock.monoMs()
        flood(packet, own = true)
        return packet
    }

    /** The console's response to a reporter; null if a later one is already known. */
    fun publishResponse(response: IncidentResponse): Packet? {
        val packet = newPacket(PacketType.INCIDENT_RESPONSE, response.encode(), null, encrypted = false, anonymous = false)
        if (!cache.offerResponse(response, packet, clock.monoMs())) return null
        saveCache()
        ownNewsMs = clock.monoMs()
        flood(packet, own = true)
        return packet
    }

    /** The reporter's update of its incident, anonymous like the report; kept for store-and-forward like it. */
    fun publishUpdate(incidentId: ByteArray, seq: Int, sealed: ByteArray): Packet {
        val payload = IncidentUpdateCodec.encode(incidentId, seq, sealed)
        val packet = newPacket(PacketType.INCIDENT_UPDATE, payload, null, encrypted = true, anonymous = true)
        if (cache.offerUpdate(incidentId, seq, packet, clock.monoMs(), own = true)) saveCache()
        ownNewsMs = clock.monoMs()
        flood(packet, own = true)
        return packet
    }

    /** The highest sequence number this phone has seen in the notice [topic], from any console. */
    fun noticeSeq(topic: Int): Long = cache.noticeSeq(topic)

    /**
     * A notice of the console's, kept for store-and-forward until it expires; null if one of its topic with a later
     * sequence number is known, or it has expired.
     */
    fun publishNotice(notice: Notice): Packet? {
        val packet = newPacket(PacketType.ANNOUNCEMENT, notice.encode(), null, encrypted = false, anonymous = false)
        if (!cache.offerNotice(notice, packet, clock.monoMs(), clock.wallMs())) return null
        saveCache()
        flood(packet, own = true)
        return packet
    }

    /** The latest update this phone holds for the incident, as it came. */
    fun cachedUpdate(incidentId: ByteArray): Packet? = cache.kept(SummaryKind.UPDATES, incidentId.toLong())

    /** No store-and-forward: the author resends it, as a new packet, until the CANCELLED ACK arrives. */
    fun publishCancel(incidentId: ByteArray, token: ByteArray): Packet {
        val payload = IncidentCancel(incidentId, token).encode()
        val packet = newPacket(PacketType.INCIDENT_CANCEL, payload, null, encrypted = false, anonymous = true)
        ownNewsMs = clock.monoMs()
        flood(packet, own = true)
        return packet
    }

    /** A report a staff phone opened as routine ([IncidentCache.settle]). */
    fun settleReport(incidentId: ByteArray) {
        cache.settle(incidentId, clock.monoMs())
        saveCache()
    }

    /** After a cancel we stop spreading our own report; the trace stays so neighbors don't bring it back. */
    fun forgetReport(incidentId: ByteArray) {
        cache.forgetReport(incidentId, clock.monoMs())
        saveCache()
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
        positionBudget.onUnique(clock.monoMs())
        return packet
    }

    private fun flood(packet: Packet, except: Set<Int> = emptySet(), own: Boolean, position: Boolean = false): Int {
        val route = routeFor(packet, except)
        val targets = if (route != null) listOf(route) else links.values.filter { it.ready && it.id !in except && canSend(it, packet) }
        var count = 0
        for (link in if (route != null) targets else fanOut(packet, targets, own)) {
            if (link.queue.offer(Outbound(packet, own, position))) count++ else drop(packet, link, DropReason.QUEUE_FULL)
        }
        return count
    }

    /**
     * With [MeshConfig.chatFanOut], a relay passes chat on a few links, the recipient's own among them; its writer and
     * every other type use them all.
     */
    private fun fanOut(packet: Packet, targets: List<Link>, own: Boolean): List<Link> {
        if (own || !config.chatFanOut || packet.type != PacketType.PRIVATE) return targets
        return FanOut.pick(targets, targets.firstOrNull { it.peerId == packet.recipient }, random)
    }

    /** With [MeshConfig.routedUnicast], the one link a PRIVATE takes toward its recipient; null to flood it. */
    private fun routeFor(packet: Packet, except: Set<Int>): Link? {
        if (!config.routedUnicast || packet.type != PacketType.PRIVATE) return null
        val recipient = packet.recipient?.takeIf { it != ControlChannel.ID } ?: return null
        val route = routes[recipient]?.takeIf { clock.monoMs() - it.atMs <= config.routeFreshMs } ?: return null
        return links[route.link]?.takeIf { it.ready && it.id !in except && canSend(it, packet) }
    }

    private fun learnRoute(peer: Long, link: Link, hops: Int, now: Long) {
        if (!config.routedUnicast || peer == 0L || peer == nodeId || link.peerId == 0L) return
        routes.remove(peer)
        routes[peer] = Route(link.id, hops, now)
        if (routes.size > MAX_ROUTES) routes.remove(routes.keys.first())
    }

    /** Nothing goes to a 1.0 peer that it would count as malformed. */
    private fun canSend(link: Link, packet: Packet): Boolean =
        (link.speaks11 || Compat10.accepts(packet)) && (link.readsAuth || !CompatAuth.needsAuth(packet))

    private fun sendLocal(link: Link, type: Int, payload: ByteArray) {
        val ttl = PacketType.originTtl(type)
        val packet = Packet(type, ttl, random.nextLong(), nodeId, clock.wallMs() / 1000, null, false, payload)
        link.queue.offer(Outbound(packet, own = true))
    }

    /**
     * The prefix goes only to a peer whose HELLO showed it reads it; nothing else may follow a 1.0 HELLO's nickname.
     * After the prefix, to a peer that reads them, the authentication fields ([authenticate]).
     */
    private fun sendHello(link: Link) {
        val nick = if (config.nicknameInHello) nickname else null
        val prefix = if (link.readsPrefix) myPrefix() else null
        val auth = prefix != null && link.readsAuth
        val proof = if (auth) link.peerNonce?.let { trust.helloProof(it, nodeId) } else null
        val flags = currentFlags() and NodeFlags.ANCHOR_SLOT.inv()
        val hello = Hello(PacketCodec.VERSION, nodeId, flags, nick, prefix, link.nonce.takeIf { auth }, proof)
        sendLocal(link, PacketType.HELLO, hello.encode())
    }

    /**
     * SUMMARY2 to a 1.1 peer, the 1.0 SUMMARY to the others. A 1.1 peer also gets a SUMMARY2 of responses and one of
     * updates, and one that set HELLO_AUTH one of notices, each only when there is something to list.
     */
    private fun sendSummary(link: Link) {
        val now = clock.monoMs()
        link.summaryAtMs = now
        val entries = cache.summary(now) { canSend(link, it) }
        if (!link.speaks11) {
            if (entries.isNotEmpty()) sendLocal(link, PacketType.SUMMARY, SummaryCodec.encode(entries))
            return
        }
        if (entries.isNotEmpty()) {
            sendLocal(link, PacketType.SUMMARY2, Summary2Codec.encode(Summary2(SummaryKind.INCIDENTS, Summary2Codec.fitting(entries))))
        }
        // a peer that can't check a notice would only relay it as a type it doesn't read
        val kinds = if (link.readsAuth) HELD_KINDS + SummaryKind.NOTICES else HELD_KINDS
        for (kind in kinds) {
            val held = cache.held(kind, now) { canSend(link, it) }
            if (held.isNotEmpty()) sendLocal(link, PacketType.SUMMARY2, Summary2Codec.encode(Summary2(kind, emptyList(), held)))
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
        if (packet.type != PacketType.HELLO && link.peerId != 0L) link.syncMs = clock.monoMs()
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
                // The advertising set starts again with a new address, so it gets a new prefix too.
                if (radioStatus.bluetoothOn && !event.status.bluetoothOn) {
                    seen.clear()
                    connecting = null
                    renewPrefix(clock.monoMs())
                }
                radioStatus = event.status
                publishState()
            }
            RadioEvent.PrefixWanted -> renewPrefix(clock.monoMs())
        }
    }

    /** A sighting without service data (the scan response was missed) keeps what the last one with it said. */
    private fun onPeerSeen(e: RadioEvent.PeerSeen) {
        val now = clock.monoMs()
        val old = seen[e.address]
        val flags = if (e.prefix == null && old != null) old.flags else e.flags
        seen[e.address] = Candidate(e.address, e.prefix ?: old?.prefix, flags, e.rssi, old?.sinceMs ?: now, now, e.inBackground)
        maintainConnections(now)
    }

    private fun onLinkUp(e: RadioEvent.LinkUp) {
        val now = clock.monoMs()
        if (e.outgoing && connecting == e.address) connecting = null
        val banned = (bannedAddressesUntil[e.address] ?: 0) > now
        val overLimit = !e.outgoing && heldInbound() >= mode.limits.maxIn
        val spare = overLimit && keepsAnchorSlot()
        val shutOut = (shutOutAddressesUntil[e.address] ?: 0) > now
        if (banned || (overLimit && !spare) || shutOut || e.maxFrame < PacketCodec.MIN_FRAME) {
            if (banned) refusedBannedCount++
            log.link(clock.wallMs(), e.address.takeLast(5), "refuzat")
            radio.disconnect(e.link)
            return
        }
        val link = Link(e.link, e.address, e.outgoing, e.maxFrame, now, seen[e.address]?.prefix, config, trust.nonce()) { queueFullCount++ }
        link.spare = spare
        links[e.link] = link
        linksOpened++
        log.link(clock.wallMs(), link.label, if (e.outgoing) "link up (out) mtu=${e.maxFrame}" else "link up (in) mtu=${e.maxFrame}")
        // phones that found the anchor together would otherwise all connect in the same second and collide
        if (!e.outgoing && mode == LinkMode.ANCHOR) admitPausedUntilMs = now + ConnectionPolicy.admissionBackoffMs(random)
        startPump(link)
        sendHello(link)
        refreshAdvertising()
        publishState()
    }

    private fun onLinkDown(id: Int) {
        val link = links.remove(id) ?: return
        link.pump?.cancel()
        linksClosed++
        val now = clock.monoMs()
        log.link(clock.wallMs(), link.label, "link down")
        if (link.peerId != 0L && ConnectionPolicy.isSession(mode, link.peerFlags)) link.peerPrefix?.let { sessionEnds[it] = now }
        // a link that drops before HELLO (refused by the peer, say) isn't retried right away
        if (link.outgoing && link.peerId == 0L) registerFailure(link.address)
        // After a lost link, forget the peer: if it's still in range we'll see its advertising within a second;
        // if it left, we won't keep connecting to its last address.
        val prefix = link.peerPrefix
        if (prefix != null && link.roleFlags != link.peerFlags) unprovenUntil[prefix] = now + ProvenRoles.UNPROVEN_MS
        seen.entries.removeAll { (address, c) -> address == link.address || (prefix != null && c.prefix == prefix) }
        if (link.peerId != 0L && links.values.none { it.peerId == link.peerId && it.ready }) {
            twinAddresses.values.removeAll { it == link.peerId }
            _events.tryEmit(MeshEvent.PeerUnlinked(link.peerId))
        }
        refreshAdvertising()
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
        link.syncMs = now

        when (packet.type) {
            PacketType.SUMMARY, PacketType.SUMMARY2 -> onSummary(link, packet)
            PacketType.REQUEST, PacketType.REQUEST2 -> onRequest(link, packet)
            PacketType.ANNOUNCEMENT -> onAnnouncement(link, packet, now)
            else -> if (PacketType.isKnown(packet.type)) onMeshPacket(link, packet, now) else onOpaque(link, packet, now)
        }
    }

    /** The link that sent it already has it, so a pending relay skips that link. */
    private fun isDuplicate(link: Link, packet: Packet, now: Long): Boolean {
        if (PacketType.isLinkLocal(packet.type) || !dedup.contains(packet.dedupKey, now)) return false
        relaying[packet.dedupKey]?.let { pending ->
            pending.delivered.add(link.id)
            if (config.bestTtl) pending.ttl = minOf(maxOf(pending.ttl, packet.ttl - 1), pending.maxTtl)
        }
        if (config.dropQueuedCopies) link.queue.removeCopies(packet.dedupKey)
        return true
    }

    private fun onHello(link: Link, packet: Packet) {
        val hello = Hello.decode(packet.payload)
        if (hello == null || hello.version < PacketCodec.VERSION || hello.nodeId != packet.sender) {
            return malformed(link, "HELLO invalid")
        }
        if (hello.nodeId == nodeId) return close(link)
        if (link.peerId != 0L) {
            if (link.peerId != hello.nodeId) return malformed(link, "HELLO with a different nodeId")
            val before = link.peerPrefix
            setPeer(link, hello)
            // the peer moved to a new prefix: what it advertised before is stale, not a new peer to connect to
            if (before != null && link.peerPrefix != before) seen.values.removeAll { it.prefix == before }
            if (authenticate(link, hello)) sendHello(link)
            publishState()
            return
        }
        log.packet(clock.wallMs(), LogKind.RX, packet, hello.nodeId.shortHex())
        val twin = links.values.firstOrNull { it !== link && it.peerId == hello.nodeId && !it.closing }
        if (twin != null) {
            val loser = duplicateLoser(older = twin, newer = link, peerId = hello.nodeId)
            log.link(clock.wallMs(), hello.nodeId.shortHex(), "duplicate link, closing the ${if (loser === link) "new" else "old"} one")
            if (loser.outgoing) twinAddresses[loser.address] = hello.nodeId
            close(loser)
            if (loser === link) return
        }
        link.peerId = hello.nodeId
        link.syncMs = clock.monoMs()
        learnRoute(hello.nodeId, link, 1, clock.monoMs())
        setPeer(link, hello)
        if (link.spare && !ConnectionPolicy.isSpareSession(mode, hello.flags)) {
            log.link(clock.wallMs(), link.label, "the anchor slot is for anchors")
            return close(link)
        }
        addressFailures.remove(link.address)
        updateMode(clock.monoMs())
        if (isIgnored(hello.nodeId)) return shutOut(link, clock.monoMs())
        // our first HELLO left before we knew the peer reads the prefix
        if (authenticate(link, hello) || link.readsPrefix) sendHello(link)
        sendSummary(link)
        _events.tryEmit(MeshEvent.PeerLinked(link.peerId))
        publishState()
    }

    private fun setPeer(link: Link, hello: Hello) {
        link.peerFlags = hello.flags
        link.nickname = hello.nickname
        if (link.readsPrefix) hello.prefix?.let { link.helloPrefix = it }
    }

    /**
     * HELLO authentication (PROTOCOL.md, section 6.1), between peers that set [NodeFlags.HELLO_AUTH]. Each end puts a
     * random nonce in its HELLOs after the prefix; an end with a shift certificate answers the peer's nonce with the
     * certificate and a signature over it, which [trust] checks against the key chain. A role the peer advertises or
     * claims counts only once proven ([Link.roleFlags]). True if the peer's nonce is new and we can answer it.
     */
    private fun authenticate(link: Link, hello: Hello): Boolean {
        if (!link.readsAuth) return false
        hello.proof?.let { link.provenRoles = trust.provenRoles(it, link.nonce, hello.nodeId) }
        val nonce = hello.nonce ?: return false
        if (link.peerNonce?.contentEquals(nonce) == true) return false
        link.peerNonce = nonce
        return trust.helloProof(nonce, nodeId) != null
    }

    /** A candidate whose last link claimed a role it didn't prove is ranked like a participant. */
    private fun candidates(now: Long): List<Candidate> =
        seen.values.map { ProvenRoles.candidate(it, it.prefix?.let(unprovenUntil::get), now) }

    /** Both ends must pick the same link without talking: keep the one initiated by the lower nodeId. */
    private fun duplicateLoser(older: Link, newer: Link, peerId: Long): Link {
        val preferOutgoing = nodeId.toULong() < peerId.toULong()
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
            if (SummaryKind.isHeld(summary.kind)) return onHeld(link, packet, summary)
            if (summary.kind != SummaryKind.INCIDENTS) return drop(packet, link, DropReason.UNSUPPORTED)
            summary.entries
        }
        log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "${entries.size} entries")
        val now = clock.monoMs()
        val wanted = cache.missing(entries, now).withinReportBudget(link.newReports.remaining(now))
        if (wanted.isNotEmpty()) sendRequest(link, packet.type, wanted)
    }

    /**
     * Responses, updates and notices later than ours are asked for by id8; updates and notices within the link's
     * budget for them.
     */
    private fun onHeld(link: Link, packet: Packet, summary: Summary2) {
        log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "${summary.held.size} entries")
        val now = clock.monoMs()
        var wanted = cache.missing(summary.kind, summary.held, now)
        if (summary.kind == SummaryKind.UPDATES) wanted = wanted.take(link.newUpdates.remaining(now))
        if (summary.kind == SummaryKind.NOTICES) wanted = wanted.take(link.announcements.remaining(now))
        if (wanted.isNotEmpty()) sendLocal(link, PacketType.REQUEST2, Request2Codec.encode(Request2(summary.kind, emptyList(), wanted)))
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
            if (SummaryKind.isHeld(request.kind)) {
                log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "${request.ids.size} entries")
                for (id8 in request.ids) cache.kept(request.kind, id8)?.let { serve(link, it) }
                return
            }
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
        if (packet.type == PacketType.INCIDENT_UPDATE && IncidentUpdateCodec.isWellFormed(packet.payload) && !link.newUpdates.tryAdd(now)) {
            return drop(packet, link, DropReason.UPDATE_LIMIT)
        }
        dedup.add(packet.dedupKey, now)
        positionBudget.onUnique(now)
        // what we keep for store-and-forward is relayed with one hop less
        val forwardable = packet.withTtl(maxOf(packet.ttl - 1, 1))
        // taken as new by the cache, which stops a second relay of it whatever the TTL says
        var cached = false
        when (packet.type) {
            PacketType.INCIDENT_REPORT -> {
                if (!IncidentReportCodec.isWellFormed(packet.payload)) return malformed(link, "invalid report")
                val incidentId = IncidentReportCodec.incidentId(packet.payload)
                if (!cache.offerReport(incidentId, forwardable, now, packet.hops)) return drop(packet, link, DropReason.KNOWN_INCIDENT)
                link.newReports.tryAdd(now)
                reportHops.bump(packet.hops)
                saveCache()
                cached = true
            }
            PacketType.INCIDENT_ACK -> {
                val ack = IncidentAck.decode(packet.payload) ?: return malformed(link, "ACK invalid")
                when (trust.checkAck(packet.payload)) {
                    Verdict.VALID -> Unit
                    Verdict.REFUSED -> return drop(packet, link, DropReason.UNTRUSTED)
                    Verdict.INVALID -> return malformed(link, "ACK with invalid signature")
                }
                // a status from a newer version has no place in the supersede order: relayed, neither kept nor delivered
                if (!AckStatus.isKnown(ack.status)) return relayOnward(link, packet)
                if (!cache.offerAck(ack, forwardable, now)) return drop(packet, link, DropReason.STALE_ACK)
                ackHops.bump(packet.hops)
                saveCache()
                cached = true
            }
            PacketType.INCIDENT_CANCEL -> {
                if (packet.payload.size < IncidentCancel.SIZE) return malformed(link, "invalid CANCEL")
            }
            PacketType.INCIDENT_RESPONSE -> {
                val response = IncidentResponse.decode(packet.payload) ?: return malformed(link, "invalid RESPONSE")
                when (trust.checkResponse(packet.payload)) {
                    Verdict.VALID -> Unit
                    Verdict.REFUSED -> return drop(packet, link, DropReason.UNTRUSTED)
                    Verdict.INVALID -> return malformed(link, "RESPONSE with invalid signature")
                }
                if (!cache.offerResponse(response, forwardable, now)) return drop(packet, link, DropReason.STALE_ACK)
                saveCache()
                cached = true
            }
            // a revocation goes on as it is; a notice only if it is its topic's latest and still holds
            PacketType.ANNOUNCEMENT -> {
                val notice = Notice.decode(packet.payload)
                if (notice != null) {
                    if (!cache.offerNotice(notice, forwardable, now, clock.wallMs())) return drop(packet, link, DropReason.STALE_ACK)
                    saveCache()
                    cached = true
                }
            }
            // relays can't check an update, so an older one still goes on live; only the latest is kept
            PacketType.INCIDENT_UPDATE -> {
                if (!IncidentUpdateCodec.isWellFormed(packet.payload)) return malformed(link, "invalid UPDATE")
                val id = IncidentUpdateCodec.incidentId(packet.payload)
                if (cache.offerUpdate(id, IncidentUpdateCodec.seq(packet.payload), forwardable, now)) {
                    saveCache()
                    cached = true
                }
            }
            PacketType.PRIVATE -> {
                if (packet.recipient == null || packet.sender == 0L || !packet.encrypted) {
                    return malformed(link, "invalid PRIVATE")
                }
            }
        }
        link.lastActivityMs = now
        link.lastInboundMs = now
        learnRoute(packet.sender, link, packet.hops, now)
        val mine = packet.recipient == nodeId
        if (packet.recipient == null || mine) {
            receivedCount++
            log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "hops=${packet.hops}")
            _events.tryEmit(MeshEvent.Received(packet, link.peerId))
        } else if (packet.recipient == ControlChannel.ID) {
            // for the console, which also passes it on, in case it isn't the only one
            _events.tryEmit(MeshEvent.Received(packet, link.peerId))
        }
        relayOnward(link, packet, cached)
        publishState()
    }

    /** Only a report that would be stored counts against the limit; copies and known incidents don't. */
    private fun isReportOverLimit(link: Link, packet: Packet, now: Long): Boolean =
        packet.type == PacketType.INCIDENT_REPORT && IncidentReportCodec.isWellFormed(packet.payload) &&
            !cache.hasSeenReport(IncidentReportCodec.incidentId(packet.payload), now) && link.newReports.isFull(now)

    /**
     * One [trust] verifies is delivered and relayed like an incident, within the link's announcement budget, since a
     * signed payload can still be sent again under new packet ids. A forgery of a kind this version reads goes no
     * further, without a malformed mark: an older relay may have passed it on in good faith. Any other goes on as a
     * type this version only relays. A copy of a notice already held is dropped before it spends the budget, which
     * also caps the notices asked for at catch-up.
     */
    private fun onAnnouncement(link: Link, packet: Packet, now: Long) {
        when (trust.checkAnnouncement(packet.payload)) {
            Verdict.VALID -> Unit
            Verdict.REFUSED -> return onOpaque(link, packet, now)
            Verdict.INVALID -> {
                dedup.add(packet.dedupKey, now)
                return drop(packet, link, DropReason.UNTRUSTED)
            }
        }
        val notice = Notice.decode(packet.payload)
        if (notice != null && notice.seq <= cache.noticeSeq(notice.topic)) {
            dedup.add(packet.dedupKey, now)
            return drop(packet, link, DropReason.STALE_ACK)
        }
        if (!link.announcements.tryAdd(now)) return drop(packet, link, DropReason.OPAQUE_LIMIT)
        onMeshPacket(link, packet, now)
    }

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
        relay(packet.withTtl(minOf(packet.ttl - 1, PacketType.OPAQUE_MAX_TTL)), link, PacketType.OPAQUE_MAX_TTL)
        publishState()
    }

    private fun relayOnward(from: Link, packet: Packet, cached: Boolean = false) {
        if (packet.recipient == nodeId) return
        if (packet.ttl > 1) relay(packet.withTtl(packet.ttl - 1), from)
        else if (cached && config.cachedPastTtl) relay(packet, from)
    }

    private fun relay(packet: Packet, from: Link, maxTtl: Int = PacketCodec.MAX_TTL) {
        if (!ConnectionPolicy.relays(mode, packet)) return
        relayedCount++
        val jitter = random.nextLong(config.relayJitterMinMs, config.relayJitterMaxMs + 1)
        val key = packet.dedupKey
        val pending = Pending(mutableSetOf(from.id), packet.ttl, maxTtl)
        relaying[key] = pending
        scope.launch {
            delay(jitter)
            relaying.remove(key)
            flood(if (pending.ttl == packet.ttl) packet else packet.withTtl(pending.ttl), except = pending.delivered, own = false)
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
        link.peerPrefix?.let { prefixCooldownUntil[it] = now + config.rotationCooldownMs }
        close(link)
    }

    /**
     * The ban falls on the address the link came from, not on the nodeId its HELLO claims: HELLO isn't
     * authenticated, so a ban on the claimed id would let anyone get another phone refused.
     */
    private fun malformed(link: Link, reason: String) {
        drops.bump(DropReason.MALFORMED)
        val now = clock.monoMs()
        log.link(clock.wallMs(), link.label, "malformed: $reason")
        link.malformedAt.addLast(now)
        while (link.malformedAt.isNotEmpty() && now - link.malformedAt.first() > config.malformedWindowMs) {
            link.malformedAt.removeFirst()
        }
        if (link.malformedAt.size < config.malformedLimit) return
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
        bannedAddressesUntil.values.removeAll { it <= now }
        shutOutAddressesUntil.values.removeAll { it <= now }
        shutOutPrefixesUntil.values.removeAll { it <= now }
        unprovenUntil.values.removeAll { it <= now }
        routes.values.removeAll { now - it.atMs > config.routeFreshMs }
        if (rotatingPrefix.isDue(now)) renewPrefix(now)
        sessionEnds.values.removeAll { now - it > ConnectionPolicy.SELF_SESSION_MS + ConnectionPolicy.SESSION_SPREAD_MS }
        updateMode(now)
        endSessions(now)

        for (link in links.values.toList()) {
            if (link.closing) continue
            if (link.peerId == 0L && now - link.upAtMs > config.helloTimeoutMs) {
                log.link(clock.wallMs(), link.label, "no HELLO")
                close(link)
            } else if (link.ready && now - link.summaryAtMs >= config.summaryIntervalMs) {
                sendSummary(link)
            }
        }

        if (connecting != null && now - connectingSinceMs > connectingTimeoutMs + CONNECT_GRACE_MS) {
            onConnectFailed(connecting!!)
        }
        if (now - lastRotationMs >= config.rotationIntervalMs) {
            lastRotationMs = now
            rotate(now)
        }
        maintainConnections(now)
        refreshAdvertising()
        publishState()
    }

    /** A participant with a link to staff or an anchor lately is a leaf; its links over a leaf's limits go once idle. */
    private fun updateMode(now: Long) {
        if (links.values.any { it.ready && it.peerFlags and (NodeFlags.STAFF or NodeFlags.ANCHOR) != 0 }) roleLinkMs = now
        val next = ConnectionPolicy.mode(
            anchor = roleFlags and NodeFlags.ANCHOR != 0,
            staff = roleFlags and NodeFlags.STAFF != 0,
            roleLinkMs = roleLinkMs.takeIf { config.leaves },
            nowMs = now,
        )
        if (next == mode) return
        log.link(clock.wallMs(), "-", "mode ${next.name.lowercase()}")
        mode = next
        refreshAdvertising()
    }

    private fun endSessions(now: Long) {
        val open = linkViews().filter { links[it.id]?.closing == false }
        for (view in open) {
            if (!ConnectionPolicy.sessionOver(mode, view, now)) continue
            val link = links[view.id] ?: continue
            log.link(clock.wallMs(), link.label, "session over")
            close(link)
        }
        for (view in ConnectionPolicy.surplus(me(), open, now)) {
            val link = links[view.id] ?: continue
            log.link(clock.wallMs(), link.label, "over the ${mode.name.lowercase()} limits")
            close(link)
        }
    }

    private fun isBlocked(c: Candidate, now: Long): Boolean {
        if ((addressBlockedUntil[c.address] ?: 0) > now || (bannedAddressesUntil[c.address] ?: 0) > now) return true
        if ((shutOutAddressesUntil[c.address] ?: 0) > now) return true
        if (c.address in twinAddresses) return true
        val prefix = c.prefix ?: return false
        return prefix in ignored || (prefixCooldownUntil[prefix] ?: 0) > now || (shutOutPrefixesUntil[prefix] ?: 0) > now
    }

    private fun linkViews(): List<LinkView> = links.values.map {
        LinkView(it.id, it.address, it.peerPrefix, it.roleFlags, it.outgoing, it.upAtMs, it.lastActivityMs, it.lastInboundMs, it.syncMs)
    }

    private fun me() = Me(myPrefix(), nodeId.nodePrefix(), radioStatus.canAdvertise, mode)

    private fun sessions() = Sessions(sessionEnds, ownNewsMs)

    private fun maintainConnections(now: Long) {
        if (connecting != null || !radioStatus.bluetoothOn) return
        val pick = ConnectionPolicy.pick(me(), candidates(now), linkViews(), now, sessions()) { isBlocked(it, now) } ?: return
        val session = ConnectionPolicy.isSession(mode, pick.flags)
        connecting = pick.address
        connectingSinceMs = now
        connectingTimeoutMs = if (session) ConnectionPolicy.SESSION_SETUP_MS else config.connectTimeoutMs
        val what = if (session) "session" else "connect"
        log.link(clock.wallMs(), pick.address.takeLast(5), "$what ${pick.prefix?.toHex() ?: "?"} rssi=${pick.rssi}")
        radio.connect(pick.address, connectingTimeoutMs)
    }

    private fun rotate(now: Long) {
        val victim = ConnectionPolicy.rotationVictim(me(), candidates(now), linkViews(), now, sessions()) { isBlocked(it, now) } ?: return
        val link = links[victim.id] ?: return
        victim.peerPrefix?.let { prefixCooldownUntil[it] = now + config.rotationCooldownMs }
        log.link(clock.wallMs(), link.label, "rotatie")
        close(link)
    }

    /** An anchor keeps its nodeId's prefix: it is a fixed device, and the control console notices a quiet anchor by it. */
    private fun myPrefix(): Int = if (roleFlags and NodeFlags.ANCHOR != 0) nodeId.nodePrefix() else rotatingPrefix.value

    /** Neighbors that read the prefix learn the new one before it is advertised, so they don't connect to it again. */
    private fun renewPrefix(now: Long) {
        val before = myPrefix()
        rotatingPrefix.renew(now)
        if (myPrefix() == before) return
        for (link in links.values) if (!link.closing && link.readsPrefix) sendHello(link)
        refreshAdvertising()
    }

    /**
     * What any scanner nearby sees and what HELLO tells a neighbor. A pending report stays out of both,
     * so the reporter isn't a beacon.
     */
    private fun currentFlags(): Int {
        var flags = roleFlags or NodeFlags.PROTOCOL_1_1 or NodeFlags.PREFIX_IN_HELLO or NodeFlags.HELLO_AUTH
        val admitting = clock.monoMs() >= admitPausedUntilMs
        if (admitting && heldInbound() < mode.limits.maxIn) flags = flags or NodeFlags.ACCEPTS_CONNECTIONS
        if (keepsAnchorSlot()) flags = flags or NodeFlags.ANCHOR_SLOT
        return flags
    }

    /** Inbound links within the limits: not a phone's session with an anchor, nor one on the slot kept for it. */
    private fun heldInbound(): Int = links.values.count {
        !it.outgoing && !it.closing && !it.spare && !(it.peerId != 0L && ConnectionPolicy.isSpareSession(mode, it.peerFlags))
    }

    /** A phone's slot for an anchor is free while it has no session with one. */
    private fun keepsAnchorSlot(): Boolean = mode != LinkMode.ANCHOR &&
        links.values.none { !it.closing && (it.spare || (it.peerId != 0L && ConnectionPolicy.isSpareSession(mode, it.peerFlags))) }


    private fun refreshAdvertising() {
        val flags = currentFlags()
        val prefix = myPrefix()
        if (flags == advertisedFlags && prefix == advertisedPrefix) return
        advertisedFlags = flags
        advertisedPrefix = prefix
        radio.setAdvertising(flags, prefix)
    }

    private fun saveCache() {
        cacheStore?.update { cache.snapshot(clock.monoMs(), clock.wallMs()) }
    }

    private fun publishState() {
        _state.value = MeshState(
            radio = radioStatus,
            links = links.values.filter { !it.closing }.map {
                // a name that passes for staff shows only next to a proven role
                val nickname = it.nickname?.takeIf { name -> it.verified || !ReservedNames.imitatesStaff(name) }
                LinkInfo(it.id, it.address, it.peerId, nickname, it.peerFlags, it.outgoing, it.upAtMs, it.peerPrefix, it.verified)
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
                reportHops.toMap(), ackHops.toMap(), linksOpened, linksClosed,
            ),
        )
    }

    private fun <K> HashMap<K, Long>.bump(key: K) {
        this[key] = (this[key] ?: 0L) + 1
    }

    companion object {
        /**
         * Off: chat floods like everything else, as it reaches farther (PROTOCOL.md, section 5). The fan-out stays,
         * so the pilot can measure what it saves and what it costs in delivery.
         */
        const val CHAT_FAN_OUT = false

        const val CONNECT_GRACE_MS = 5_000L

        /**
         * Off: a leaf trusts the role its neighbor claims in HELLO, so a phone that only claims one could turn the
         * phones around it into leaves and cut the chat between them; and with most phones down to two links, the
         * leaves of a crowd split into chains (PROTOCOL.md, section 14). On once roles are signed (2.18).
         */
        const val LEAVES = false

        /** What a 1.1 peer gets summaries of besides incidents. */
        private val HELD_KINDS = listOf(SummaryKind.RESPONSES, SummaryKind.UPDATES)

        /** How long a route learned from a phone's packet is followed by default ([MeshConfig.routeFreshMs]). */
        const val ROUTE_FRESH_MS = 120_000L
        private const val MAX_ROUTES = 4_096
    }
}
