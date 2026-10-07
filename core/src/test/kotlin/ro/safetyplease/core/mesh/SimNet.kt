package ro.safetyplease.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import ro.safetyplease.core.util.Clock

class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun wallMs(): Long = 1_760_000_000_000L + scheduler.currentTime
    override fun monoMs(): Long = scheduler.currentTime
}

/**
 * An in-memory "ether": every radio sees every other, except the pairs in [outOfRange].
 * As on Android, an initiator shows up at the target under a different address than the one it advertises.
 */
class SimNet(private val scope: CoroutineScope) {
    private val radios = mutableListOf<SimRadio>()
    private var nextLink = 1
    val outOfRange = mutableSetOf<Set<String>>()
    var connectDelayMs = 300L
    var frameDelayMs = 5L

    fun radio(address: String, prefix: Int, maxFrame: Int = 514, canAdvertise: Boolean = true): SimRadio =
        SimRadio(this, address, prefix, maxFrame, canAdvertise).also { radios += it }

    fun start() {
        radios.forEach { it.announceStatus() }
        scope.launch {
            while (isActive) {
                delay(1000)
                for (a in radios) for (b in radios) {
                    if (a !== b && a.on && b.on && a.canAdvertise && inRange(a, b)) {
                        b.events.trySend(RadioEvent.PeerSeen(a.address, a.prefix, a.flags, -60))
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

    private fun inRange(a: SimRadio, b: SimRadio) = setOf(a.address, b.address) !in outOfRange

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
        val out = tamper?.let { it(frame) ?: return true } ?: frame
        peer.radio.events.trySend(RadioEvent.Frame(peer.link, out))
        return true
    }

    /** Injects a raw frame on a link, like a peer that ignores the protocol. */
    fun inject(link: Int, frame: ByteArray) {
        val peer = links[link] ?: return
        peer.radio.events.trySend(RadioEvent.Frame(peer.link, frame))
    }

    override fun setAdvertisedFlags(flags: Int) {
        this.flags = flags
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
