package ro.safetyplease.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ro.safetyplease.core.protocol.Frozen10
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Reassembler
import ro.safetyplease.core.protocol.RequestEntry
import ro.safetyplease.core.protocol.SummaryEntry
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.toLong
import kotlin.random.Random

/**
 * A phone still on protocol 1.0, for the interop tests. It reads and writes only through [Frozen10], answers SUMMARY
 * and REQUEST, keeps reports and ACKs and relays as 1.0 did, and counts in [malformed] every packet 1.0 would have
 * counted: five in a minute would have got the sender banned. It never connects by itself; 1.1 phones connect to it.
 */
class V10Node(
    private val scope: CoroutineScope,
    net: SimNet,
    name: String,
    val nodeId: Long,
    private val clock: Clock,
    private val verifyAck: (ByteArray) -> Boolean,
) {
    private class Link(val maxFrame: Int) {
        var peerId = 0L
        var fragSeq = 0
        val reassembler = Reassembler()
    }

    val radio = net.radio(name, nodeId.nodePrefix())
    private val random = Random(nodeId)
    private val links = HashMap<Int, Link>()
    private val seen = HashSet<Long>()
    private val reports = LinkedHashMap<Long, Packet>()
    private val acks = LinkedHashMap<Long, Pair<Int, Packet>>()

    /** Packets handed to the app: addressed to this phone or to everyone. */
    val received = mutableListOf<Packet>()

    /** The type of every complete packet that arrived, readable or not. */
    val arrived = mutableListOf<Int>()
    var malformed = 0
        private set

    init {
        radio.setAdvertisedFlags(NodeFlags.ACCEPTS_CONNECTIONS)
        scope.launch { for (event in radio.events) handle(event) }
    }

    fun publishReport(incidentId: ByteArray, sealed: ByteArray): Packet {
        val packet = newPacket(PacketType.INCIDENT_REPORT, incidentId + sealed, sender = 0L, encrypted = true)
        reports[incidentId.toLong()] = packet
        flood(packet, except = null)
        return packet
    }

    fun publishAck(ack: IncidentAck): Packet {
        val packet = newPacket(PacketType.INCIDENT_ACK, Frozen10.encodeAck(ack), sender = nodeId, encrypted = false)
        acks[ack.incidentId.toLong()] = ack.status to packet
        flood(packet, except = null)
        return packet
    }

    fun unicast(recipient: Long, payload: ByteArray): Packet {
        val packet = newPacket(PacketType.PRIVATE, payload, sender = nodeId, encrypted = true, recipient = recipient)
        flood(packet, except = null)
        return packet
    }

    /** Asks every neighbor for the report and the ACK of [id8], as after a summary that listed both. */
    fun request(id8: Long) {
        val payload = Frozen10.encodeRequest(listOf(RequestEntry(id8, wantReport = true, wantAck = true)))
        for ((id, link) in links) if (link.peerId != 0L) sendLocal(id, PacketType.REQUEST, payload)
    }

    fun ackStatus(incidentId: ByteArray): Int = acks[incidentId.toLong()]?.first ?: 0

    private fun newPacket(type: Int, payload: ByteArray, sender: Long, encrypted: Boolean, recipient: Long? = null): Packet {
        val packet = Packet(type, 7, random.nextLong(), sender, clock.wallMs() / 1000, recipient, encrypted, payload)
        seen += packet.dedupKey
        return packet
    }

    private fun handle(event: RadioEvent) {
        when (event) {
            is RadioEvent.LinkUp -> {
                links[event.link] = Link(event.maxFrame)
                val hello = Frozen10.encodeHello(nodeId, NodeFlags.ACCEPTS_CONNECTIONS, null)
                sendLocal(event.link, PacketType.HELLO, hello)
            }
            is RadioEvent.LinkDown -> links.remove(event.link)
            is RadioEvent.Frame -> onFrame(event.link, event.bytes)
            else -> Unit
        }
    }

    private fun onFrame(linkId: Int, bytes: ByteArray) {
        val link = links[linkId] ?: return
        val frame = Frozen10.frame(bytes) ?: return bad()
        val packet = when (val result = link.reassembler.accept(frame, clock.monoMs())) {
            is Reassembler.Result.Complete -> result.packet
            Reassembler.Result.Pending -> return
            Reassembler.Result.Invalid -> return bad()
        }
        arrived += packet.type
        if (packet.type == PacketType.HELLO) return onHello(linkId, link, packet)
        if (link.peerId == 0L) return
        when (packet.type) {
            PacketType.SUMMARY -> onSummary(linkId, packet)
            PacketType.REQUEST -> onRequest(linkId, packet)
            else -> onMeshPacket(linkId, packet)
        }
    }

    private fun onHello(linkId: Int, link: Link, packet: Packet) {
        val hello = Frozen10.hello(packet.payload)
        if (hello == null || hello.nodeId != packet.sender) return bad()
        if (link.peerId != 0L) return
        link.peerId = hello.nodeId
        val ids = (reports.keys + acks.keys).distinct().take(50)
        if (ids.isEmpty()) return
        val entries = ids.map { SummaryEntry(it, it in reports, acks[it]?.first ?: 0) }
        sendLocal(linkId, PacketType.SUMMARY, Frozen10.encodeSummary(entries))
    }

    private fun onSummary(linkId: Int, packet: Packet) {
        val entries = Frozen10.summary(packet.payload) ?: return bad()
        val wanted = entries.mapNotNull { e ->
            val wantReport = e.hasReport && e.id8 !in reports
            val wantAck = e.ackStatus > (acks[e.id8]?.first ?: 0)
            if (wantReport || wantAck) RequestEntry(e.id8, wantReport, wantAck) else null
        }
        if (wanted.isNotEmpty()) sendLocal(linkId, PacketType.REQUEST, Frozen10.encodeRequest(wanted))
    }

    private fun onRequest(linkId: Int, packet: Packet) {
        val entries = Frozen10.request(packet.payload) ?: return bad()
        for (e in entries) {
            if (e.wantReport) reports[e.id8]?.let { send(linkId, it) }
            if (e.wantAck) acks[e.id8]?.let { send(linkId, it.second) }
        }
    }

    private fun onMeshPacket(linkId: Int, packet: Packet) {
        if (!seen.add(packet.dedupKey)) return
        if (!Frozen10.payloadOk(packet)) return bad()
        if (packet.type == PacketType.INCIDENT_ACK && !verifyAck(packet.payload)) return bad()
        val stored = packet.withTtl(maxOf(packet.ttl - 1, 1))
        val id8 = if (packet.payload.size >= INCIDENT_ID_SIZE) packet.payload.toLong() else 0L
        when (packet.type) {
            PacketType.INCIDENT_REPORT -> if (reports.putIfAbsent(id8, stored) != null) return
            PacketType.INCIDENT_ACK -> {
                val status = Frozen10.ack(packet.payload)!!.status
                if (status <= (acks[id8]?.first ?: 0)) return
                acks[id8] = status to stored
            }
        }
        val mine = packet.recipient == nodeId
        if (packet.recipient == null || mine) received += packet
        if (!mine && packet.ttl > 1) flood(packet.withTtl(packet.ttl - 1), except = linkId)
    }

    private fun bad() {
        malformed++
    }

    private fun sendLocal(linkId: Int, type: Int, payload: ByteArray) =
        send(linkId, Packet(type, 1, random.nextLong(), nodeId, clock.wallMs() / 1000, null, false, payload))

    private fun flood(packet: Packet, except: Int?) {
        for ((id, link) in links) if (id != except && link.peerId != 0L) send(id, packet)
    }

    private fun send(linkId: Int, packet: Packet) {
        val link = links[linkId] ?: return
        val frames = Frozen10.encodeFrames(packet, link.maxFrame, link.fragSeq++)
        scope.launch { for (frame in frames) radio.send(linkId, frame) }
    }
}
