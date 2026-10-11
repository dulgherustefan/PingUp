package ro.safetyplease.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.util.Clock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun wallMs(): Long = 1_760_000_000_000L + scheduler.currentTime
    override fun monoMs(): Long = scheduler.currentTime
}

data class Position(val x: Double, val y: Double)

/**
 * An in-memory "ether": every radio sees every other, except the pairs in [outOfRange].
 * Radios placed by [grid] or [scatter] must also be within [range] of each other, and can [walk] around.
 * As on Android, an initiator shows up at the target under a different address than the one it advertises.
 */
class SimNet(private val scope: CoroutineScope) {
    private val radios = mutableListOf<SimRadio>()
    private var nextLink = 1
    val outOfRange = mutableSetOf<Set<String>>()
    var connectDelayMs = 300L
    var frameDelayMs = 5L

    /** How far a placed radio is heard by another placed one; radios without a position hear everyone. */
    var range = Double.POSITIVE_INFINITY
    private var width = 0.0
    private var height = 0.0

    /** Every packet put on the air, by packet id: its type and how many times it was transmitted in the whole net. */
    val transmissions = HashMap<Long, Transmissions>()

    class Transmissions(val type: Int) {
        var count = 0
    }

    class Traffic(val packets: Int, val transmissions: Int) {
        val perPacket: Double get() = if (packets == 0) 0.0 else transmissions.toDouble() / packets
    }

    fun radio(address: String, prefix: Int, maxFrame: Int = 514, canAdvertise: Boolean = true): SimRadio =
        SimRadio(this, address, prefix, maxFrame, canAdvertise).also { radios += it }

    /** Rows of [columns] radios, [spacing] apart. */
    fun grid(radios: List<SimRadio>, columns: Int, spacing: Double) {
        radios.forEachIndexed { i, r -> r.position = Position(i % columns * spacing, i / columns * spacing) }
        width = (columns - 1) * spacing
        height = (radios.size - 1) / columns * spacing
    }

    /** Uniformly at random over a [width] x [height] area. */
    fun scatter(radios: List<SimRadio>, width: Double, height: Double, random: Random) {
        for (r in radios) r.position = Position(random.nextDouble(width), random.nextDouble(height))
        this.width = width
        this.height = height
    }

    /**
     * Random walk: every [everyMs] each placed radio moves up to [step] in a random direction, staying inside
     * the area. Links between radios that end up out of range drop, as when people walk apart.
     */
    fun walk(random: Random, step: Double, everyMs: Long = 1_000) {
        scope.launch {
            while (isActive) {
                delay(everyMs)
                for (r in radios) {
                    val p = r.position ?: continue
                    val angle = random.nextDouble(2 * PI)
                    val d = random.nextDouble(step)
                    r.position = Position((p.x + d * cos(angle)).coerceIn(0.0, width), (p.y + d * sin(angle)).coerceIn(0.0, height))
                }
                for (r in radios) {
                    for ((id, peer) in r.links.entries.toList()) if (!inRange(r, peer.radio)) r.disconnect(id)
                }
            }
        }
    }

    /** True if every powered radio can reach every other through radios in range of each other. */
    fun connected(): Boolean {
        val on = radios.filter { it.on }
        val reached = on.take(1).toMutableSet()
        val todo = ArrayDeque(reached)
        while (todo.isNotEmpty()) {
            val r = todo.removeFirst()
            for (o in on) if (o !in reached && inRange(r, o)) {
                reached += o
                todo += o
            }
        }
        return reached.size == on.size
    }

    /** Unique packets of [type] put on the air and the transmissions they took, relays and resends included. */
    fun traffic(type: Int): Traffic {
        val ofType = transmissions.values.filter { it.type == type }
        return Traffic(ofType.size, ofType.sumOf { it.count })
    }

    internal fun onAir(frame: ByteArray) {
        val decoded = PacketCodec.decode(frame) ?: return
        // a fragmented packet counts once, on its first fragment
        if ((decoded.fragment?.index ?: 0) != 0) return
        transmissions.getOrPut(decoded.packet.id) { Transmissions(decoded.packet.type) }.count++
    }

    fun start() {
        radios.forEach { it.announceStatus() }
        scope.launch {
            while (isActive) {
                delay(1000)
                for (a in radios) for (b in radios) {
                    if (a !== b && a.on && b.on && a.canAdvertise && inRange(a, b)) {
                        b.events.trySend(RadioEvent.PeerSeen(a.address, a.prefix, a.flags, rssi(a, b)))
                    }
                }
            }
        }
    }

    fun cut(a: String, b: String) {
        outOfRange += setOf(a, b)
        for (r in radios) r.dropLinksTo(if (r.address == a) b else if (r.address == b) a else continue)
    }

    fun heal(a: String, b: String) {
        outOfRange -= setOf(a, b)
    }

    private fun inRange(a: SimRadio, b: SimRadio): Boolean {
        val pa = a.position
        val pb = b.position
        if (pa != null && pb != null && hypot(pa.x - pb.x, pa.y - pb.y) > range) return false
        return outOfRange.isEmpty() || setOf(a.address, b.address) !in outOfRange
    }

    /** Weaker with distance between placed radios, so the policy prefers near peers as it does over the air. */
    private fun rssi(a: SimRadio, b: SimRadio): Int {
        val pa = a.position ?: return -60
        val pb = b.position ?: return -60
        return (-50 - 20 * log10(maxOf(hypot(pa.x - pb.x, pa.y - pb.y), 1.0))).roundToInt()
    }

    internal fun connect(from: SimRadio, address: String) {
        scope.launch {
            delay(connectDelayMs)
            val target = radios.firstOrNull { it.address == address }
            if (target == null || !target.on || !from.on || !inRange(from, target)) {
                from.events.trySend(RadioEvent.ConnectFailed(address))
                return@launch
            }
            val a = nextLink++
            val b = nextLink++
            val frame = minOf(from.maxFrame, target.maxFrame)
            from.links[a] = SimRadio.Peer(target, b)
            target.links[b] = SimRadio.Peer(from, a)
            from.events.trySend(RadioEvent.LinkUp(a, address, true, frame))
            target.events.trySend(RadioEvent.LinkUp(b, "c:${from.address}", false, frame))
        }
    }
}

class SimRadio(
    private val net: SimNet,
    val address: String,
    val prefix: Int,
    val maxFrame: Int,
    val canAdvertise: Boolean,
) : Radio {
    class Peer(val radio: SimRadio, val link: Int)

    override val events = Channel<RadioEvent>(Channel.UNLIMITED)
    val links = HashMap<Int, Peer>()
    var flags = 0
    var on = true
    var position: Position? = null
    val connectAttempts = mutableListOf<String>()
    var framesSent = 0

    /** If set, outgoing frames pass through here first; null means "drop the frame". */
    var tamper: ((ByteArray) -> ByteArray?)? = null

    fun announceStatus() {
        events.trySend(RadioEvent.Status(RadioStatus(on, on, on && canAdvertise, canAdvertise)))
    }

    override fun connect(address: String) {
        connectAttempts += address
        net.connect(this, address)
    }

    override fun disconnect(link: Int) {
        val peer = links.remove(link) ?: return
        events.trySend(RadioEvent.LinkDown(link))
        if (peer.radio.links.remove(peer.link) != null) peer.radio.events.trySend(RadioEvent.LinkDown(peer.link))
    }

    override suspend fun send(link: Int, frame: ByteArray): Boolean {
        if (links[link] == null) return false
        delay(net.frameDelayMs)
        val peer = links[link] ?: return false
        framesSent++
        net.onAir(frame)
        val out = tamper?.let { it(frame) ?: return true } ?: frame
        peer.radio.events.trySend(RadioEvent.Frame(peer.link, out))
        return true
    }

    /** Injects a raw frame on a link, like a peer that ignores the protocol. */
    fun inject(link: Int, frame: ByteArray) {
        val peer = links[link] ?: return
        peer.radio.events.trySend(RadioEvent.Frame(peer.link, frame))
    }

    /** Every flag set the engine asked to advertise, in order. */
    val advertised = mutableListOf<Int>()

    override fun setAdvertisedFlags(flags: Int) {
        this.flags = flags
        advertised += flags
    }

    override fun setPowerMode(scan: PowerMode, advertise: PowerMode) = Unit

    fun dropLinksTo(address: String) {
        for (id in links.filterValues { it.radio.address == address }.keys.toList()) disconnect(id)
    }

    fun powerOff() {
        on = false
        for (id in links.keys.toList()) disconnect(id)
        announceStatus()
    }

    fun powerOn() {
        on = true
        announceStatus()
    }
}
