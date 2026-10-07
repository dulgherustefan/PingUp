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
import ro.safetyplease.core.protocol.RequestCodec
import ro.safetyplease.core.protocol.SummaryCodec
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
    /** 20 de pachete pe secunda per legatura. */
    val minSendSpacingMs: Long = 50,
    val rxBurst: Double = 30.0,
    val rxPerSecond: Double = 3.0,
    // putin sub limita de la primire, ca un nod corect sa nu fie niciodata taiat de vecin
    val txBurst: Double = 28.0,
    val txPerSecond: Double = 2.8,
    val retentionMs: Long = 30 * 60_000L,
    val anchorRetentionMs: Long = 60 * 60_000L,
    /** Nickname-ul apare in HELLO doar in build-ul debug, pentru lista de peer-i din modul demo. */
    val nicknameInHello: Boolean = false,
    val allowTestPackets: Boolean = false,
)

sealed interface MeshEvent {
    class Received(val packet: Packet, val viaPeer: Long) : MeshEvent

    /** Un pachet propriu a fost predat cel putin unei legaturi. */
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

data class MeshState(
    val radio: RadioStatus = RadioStatus(),
    val links: List<LinkInfo> = emptyList(),
    val seen: List<SeenPeer> = emptyList(),
    val sent: Long = 0,
    val received: Long = 0,
    val relayed: Long = 0,
    val dropped: Long = 0,
    val cachedReports: Int = 0,
) {
    val readyLinks: Int get() = links.count { it.peerId != 0L && !it.muted }

    /** Telefoane, nu adrese: acelasi peer poate aparea scurt timp sub doua adrese dupa ce isi reporneste advertising-ul. */
    val visiblePeers: Int get() = seen.map { it.prefix ?: it.address.hashCode() }.distinct().size
}

/**
 * Nucleul mesh-ului, independent de radio: legaturi, HELLO, dedup, relay cu TTL, cozi cu prioritati,
 * limite anti-abuz si store-and-forward. Toata starea e atinsa dintr-un singur fir (contextul lui [scope]).
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
        val queue = SendQueue()
        val reassembler = Reassembler()
        val rx = TokenBucket(config.rxBurst, config.rxPerSecond, upAtMs)
        val tx = TokenBucket(config.txBurst, config.txPerSecond, upAtMs)
        val malformedAt = ArrayDeque<Long>()

        val ready: Boolean get() = peerId != 0L && !muted && !closing
        val label: String get() = if (peerId != 0L) peerId.shortHex() else address.takeLast(5)
    }

    private val myPrefix = nodeId.nodePrefix()
    private val links = LinkedHashMap<Int, Link>()
    private val seen = HashMap<String, Candidate>()
    private val dedup = DedupCache()
    private val cache = IncidentCache(config.retentionMs)

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
    private var pendingIncident = false
    private var advertisedFlags = -1

    private var sentCount = 0L
    private var receivedCount = 0L
    private var relayedCount = 0L
    private var droppedCount = 0L

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

    // --- configurare ---

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

    fun setPendingIncident(pending: Boolean) {
        if (pendingIncident == pending) return
        pendingIncident = pending
        refreshFlags()
    }

    /** Modul demo: nu ne conectam la aceste prefixe si aruncam tot ce vine de la ele sau ar pleca spre ele. */
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

    // --- trimitere ---

    fun broadcast(type: Int, payload: ByteArray, encrypted: Boolean = false, anonymous: Boolean = false): Packet {
        val packet = newPacket(type, payload, null, encrypted, anonymous)
        flood(packet, exceptLink = null, own = true)
        return packet
    }

    fun unicast(type: Int, recipient: Long, payload: ByteArray, encrypted: Boolean = true): Packet {
        val packet = newPacket(type, payload, recipient, encrypted, anonymous = false)
        flood(packet, exceptLink = null, own = true)
        return packet
    }

    fun publishReport(incidentId: ByteArray, sealed: ByteArray): Packet {
        val payload = IncidentReportCodec.encode(incidentId, sealed)
        val packet = newPacket(PacketType.INCIDENT_REPORT, payload, null, encrypted = true, anonymous = true)
        cache.offerReport(incidentId, packet, clock.monoMs())
        flood(packet, exceptLink = null, own = true)
        publishState()
        return packet
    }

    fun publishAck(ack: IncidentAck): Packet? {
        val packet = newPacket(PacketType.INCIDENT_ACK, ack.encode(), null, encrypted = false, anonymous = false)
        if (!cache.offerAck(ack, packet, clock.monoMs())) return null
        flood(packet, exceptLink = null, own = true)
        return packet
    }

    /** Fara store-and-forward: autorul o retrimite, ca pachet nou, pana vine ACK-ul CANCELLED. */
    fun publishCancel(incidentId: ByteArray, token: ByteArray): Packet {
        val payload = IncidentCancel(incidentId, token).encode()
        val packet = newPacket(PacketType.INCIDENT_CANCEL, payload, null, encrypted = false, anonymous = true)
        flood(packet, exceptLink = null, own = true)
        return packet
    }

    /** Dupa o anulare nu mai raspandim propriul raport; urma ramane, ca vecinii sa nu ni-l aduca inapoi. */
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
            ttl = PacketCodec.MAX_TTL,
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

    private fun flood(packet: Packet, exceptLink: Int?, own: Boolean): Int {
        var count = 0
        for (link in links.values) {
            if (!link.ready || link.id == exceptLink) continue
            if (link.queue.offer(Outbound(packet, own))) count++ else drop(packet, link, "queue full")
        }
        return count
    }

    private fun sendLocal(link: Link, type: Int, payload: ByteArray) {
        val packet = Packet(type, 1, random.nextLong(), nodeId, clock.wallMs() / 1000, null, false, payload)
        link.queue.offer(Outbound(packet, own = true))
    }

    private fun sendHello(link: Link) {
        val nick = if (config.nicknameInHello) nickname else null
        sendLocal(link, PacketType.HELLO, Hello(PacketCodec.VERSION, nodeId, currentFlags(), nick).encode())
    }

    private fun sendSummary(link: Link) {
        val now = clock.monoMs()
        link.summaryAtMs = now
        val entries = cache.summary(now)
        if (entries.isNotEmpty()) sendLocal(link, PacketType.SUMMARY, SummaryCodec.encode(entries))
    }

    private fun startPump(link: Link) {
        link.pump = scope.launch {
            while (isActive) {
                link.queue.awaitItem()
                val now = clock.monoMs()
                val wait = maxOf(link.nextSendAtMs - now, link.tx.waitMs(now))
                if (wait > 0) delay(wait)
                // alegerea se face abia dupa asteptare: un incident sosit intre timp trece in fata
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
        if (PacketType.isLinkLocal(packet.type)) return
        link.lastActivityMs = clock.monoMs()
        if (out.own) {
            sentCount++
            _events.tryEmit(MeshEvent.Sent(packet.id))
        }
    }

    // --- evenimente radio ---

    private fun handle(event: RadioEvent) {
        when (event) {
            is RadioEvent.PeerSeen -> onPeerSeen(event)
            is RadioEvent.LinkUp -> onLinkUp(event)
            is RadioEvent.LinkDown -> onLinkDown(event.link)
            is RadioEvent.Frame -> onFrame(event.link, event.bytes)
            is RadioEvent.ConnectFailed -> onConnectFailed(event.address)
            is RadioEvent.Status -> {
                // Cu radioul oprit, tot ce am vazut e expirat: dupa repornire asteptam advertising proaspat.
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
            log.link(clock.wallMs(), e.address.takeLast(5), "refuzat")
            radio.disconnect(e.link)
            return
        }
        val link = Link(e.link, e.address, e.outgoing, e.maxFrame, now, seen[e.address]?.prefix, config)
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
        // o legatura care pica inainte de HELLO (refuzata de peer, de exemplu) nu se reincearca imediat
        if (link.outgoing && link.peerId == 0L) registerFailure(link.address)
        // Dupa o legatura pierduta uitam peer-ul: daca mai e in raza, il revedem in advertising intr-o secunda;
        // daca a plecat, nu ne mai conectam in gol la ultima lui adresa.
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
        // Doua noduri care esueaza impreuna catre acelasi peer ar reincerca impreuna si s-ar incurca din nou.
        // Asteptarea e aleatoare intre 0,5x si 1,5x din baza, cu aceeasi medie.
        val backoff = base / 2 + random.nextLong(base)
        addressBlockedUntil[address] = clock.monoMs() + backoff
        log.link(clock.wallMs(), address.takeLast(5), "connect failed, retry in ${backoff / 100 / 10.0}s")
    }

    private fun onFrame(linkId: Int, bytes: ByteArray) {
        val link = links[linkId] ?: return
        if (link.closing) return
        val now = clock.monoMs()
        val frame = PacketCodec.decode(bytes) ?: return malformed(link, "cadru invalid")
        val packet = when (val result = link.reassembler.accept(frame, now)) {
            is Reassembler.Result.Complete -> result.packet
            Reassembler.Result.Pending -> return
            Reassembler.Result.Invalid -> return malformed(link, "fragmente invalide")
        }
        if (!link.rx.tryTake(now)) return drop(packet, link, "rate limit")
        if (packet.type == PacketType.TEST && !config.allowTestPackets) return drop(packet, link, "test")

        if (packet.type == PacketType.HELLO) return onHello(link, packet)
        if (link.peerId == 0L) return drop(packet, link, "inainte de HELLO")
        if (link.muted) return drop(packet, link, "ignorat")

        when (packet.type) {
            PacketType.SUMMARY -> onSummary(link, packet)
            PacketType.REQUEST -> onRequest(link, packet)
            else -> onMeshPacket(link, packet, now)
        }
    }

    private fun onHello(link: Link, packet: Packet) {
        val hello = Hello.decode(packet.payload)
        if (hello == null || hello.version != PacketCodec.VERSION || hello.nodeId != packet.sender) {
            return malformed(link, "HELLO invalid")
        }
        val now = clock.monoMs()
        if (hello.nodeId == nodeId || (bannedNodesUntil[hello.nodeId] ?: 0) > now) {
            close(link)
            return
        }
        if (link.peerId != 0L) {
            if (link.peerId != hello.nodeId) return malformed(link, "HELLO cu alt nodeId")
            link.peerFlags = hello.flags
            link.nickname = hello.nickname
            publishState()
            return
        }
        log.packet(clock.wallMs(), LogKind.RX, packet, hello.nodeId.shortHex())
        val twin = links.values.firstOrNull { it !== link && it.peerId == hello.nodeId && !it.closing }
        if (twin != null) {
            val loser = duplicateLoser(older = twin, newer = link, peerId = hello.nodeId)
            log.link(clock.wallMs(), hello.nodeId.shortHex(), "legatura dubla, inchid ${if (loser === link) "noua" else "veche"}")
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

    /** Ambele capete trebuie sa aleaga la fel fara sa vorbeasca: ramane legatura initiata de nodeId-ul mai mic. */
    private fun duplicateLoser(older: Link, newer: Link, peerId: Long): Link {
        val preferOutgoing = java.lang.Long.compareUnsigned(nodeId, peerId) < 0
        val olderPreferred = older.outgoing == preferOutgoing
        val newerPreferred = newer.outgoing == preferOutgoing
        return if (olderPreferred && !newerPreferred) newer else older
    }

    private fun onSummary(link: Link, packet: Packet) {
        val entries = SummaryCodec.decode(packet.payload) ?: return malformed(link, "SUMMARY invalid")
        log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "${entries.size} intrari")
        val wanted = cache.missing(entries, clock.monoMs())
        if (wanted.isNotEmpty()) sendLocal(link, PacketType.REQUEST, RequestCodec.encode(wanted))
    }

    private fun onRequest(link: Link, packet: Packet) {
        val entries = RequestCodec.decode(packet.payload) ?: return malformed(link, "REQUEST invalid")
        log.packet(clock.wallMs(), LogKind.RX, packet, link.label, "${entries.size} intrari")
        for (e in entries) {
            if (e.wantReport) cache.report(e.id8)?.let { link.queue.offer(Outbound(it, own = false)) }
            if (e.wantAck) cache.ack(e.id8)?.let { link.queue.offer(Outbound(it, own = false)) }
        }
    }

    private fun onMeshPacket(link: Link, packet: Packet, now: Long) {
        if (dedup.checkAndAdd(packet.dedupKey, now)) return drop(packet, link, "duplicat")
        // ce pastram pentru store-and-forward pleaca mai departe cu un hop in minus
        val forwardable = packet.withTtl(maxOf(packet.ttl - 1, 1))
        when (packet.type) {
            PacketType.INCIDENT_REPORT -> {
                if (!IncidentReportCodec.isWellFormed(packet.payload)) return malformed(link, "raport invalid")
                val incidentId = IncidentReportCodec.incidentId(packet.payload)
                if (!cache.offerReport(incidentId, forwardable, now, packet.hops)) return drop(packet, link, "incident cunoscut")
            }
            PacketType.INCIDENT_ACK -> {
                val ack = IncidentAck.decode(packet.payload) ?: return malformed(link, "ACK invalid")
                if (!verifyAck(packet.payload)) return malformed(link, "ACK cu semnatura invalida")
                if (!cache.offerAck(ack, forwardable, now)) return drop(packet, link, "ACK depasit")
            }
            PacketType.INCIDENT_CANCEL -> {
                if (packet.payload.size != IncidentCancel.SIZE) return malformed(link, "CANCEL invalid")
            }
            PacketType.PRIVATE -> {
                if (packet.recipient == null || packet.sender == 0L || !packet.encrypted) {
                    return malformed(link, "PRIVATE invalid")
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
        if (!mine && packet.ttl > 1) relay(packet.withTtl(packet.ttl - 1), link)
        publishState()
    }

    private fun relay(packet: Packet, from: Link) {
        relayedCount++
        val jitter = random.nextLong(config.relayJitterMinMs, config.relayJitterMaxMs + 1)
        val fromId = from.id
        scope.launch {
            delay(jitter)
            flood(packet, exceptLink = fromId, own = false)
        }
    }

    private fun drop(packet: Packet, link: Link, reason: String) {
        droppedCount++
        log.packet(clock.wallMs(), LogKind.DROP, packet, link.label, reason)
    }

    private fun malformed(link: Link, reason: String) {
        droppedCount++
        val now = clock.monoMs()
        log.link(clock.wallMs(), link.label, "malformat: $reason")
        link.malformedAt.addLast(now)
        while (link.malformedAt.isNotEmpty() && now - link.malformedAt.first() > config.malformedWindowMs) {
            link.malformedAt.removeFirst()
        }
        if (link.malformedAt.size < config.malformedLimit) return
        if (link.peerId != 0L) bannedNodesUntil[link.peerId] = now + config.banMs
        bannedAddressesUntil[link.address] = now + config.banMs
        log.link(clock.wallMs(), link.label, "ignorat ${config.banMs / 60_000} min")
        close(link)
    }

    private fun close(link: Link) {
        if (link.closing) return
        link.closing = true
        link.pump?.cancel()
        radio.disconnect(link.id)
    }

    // --- intretinere periodica ---

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
                log.link(clock.wallMs(), link.label, "fara HELLO")
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

    private fun currentFlags(): Int {
        var flags = roleFlags
        if (pendingIncident) flags = flags or NodeFlags.PENDING_INCIDENT
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
            dropped = droppedCount,
            cachedReports = cache.reportCount,
        )
    }
}
