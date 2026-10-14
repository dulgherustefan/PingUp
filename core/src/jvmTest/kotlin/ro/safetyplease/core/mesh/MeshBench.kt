package ro.safetyplease.core.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assume
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Hello
import ro.safetyplease.core.protocol.HelloProof
import ro.safetyplease.core.protocol.INCIDENT_ID_SIZE
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.protocol.IncidentReportCodec
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.nodePrefix
import ro.safetyplease.core.util.toLong
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.random.nextLong
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Crowd benchmark of the mesh: the real [MeshEngine] on hundreds to thousands of simulated phones, with the numbers
 * that matter at a festival (delivery, latency, transmissions per delivered packet, drops by reason, link churn).
 *
 * By default only [smoke] runs, small and quick. The scenarios run when the system property `mesh.bench` or the
 * environment variable `MESH_BENCH` is `all` or names them (`reports,notice,unicast,mixed,topology`). Other variables:
 * `MESH_BENCH_N` the crowd sizes (`500,1000`), `MESH_BENCH_S` the phones within radio range (`20`), `MESH_BENCH_LOAD`
 * the mixed load factors (`1,2,4`), `MESH_BENCH_GROUPS=1` positions shared both ways in groups of 2 to 4,
 * `MESH_BENCH_SHAPE=strip` a crowd four times as long as wide (a beach),
 * `MESH_BENCH_STAFF` one staff phone in how many (`100`), `MESH_BENCH_AIR` the radio ([air]) and
 * `MESH_BENCH_VARIANT` the engine variant ([variantConfig]). Each scenario prints a table and writes it under
 * `core/build/mesh-bench/`. Run one scenario per Gradle call, with `--rerun` (the variables aren't task inputs): the
 * test task stops after 15 minutes. Example, from PowerShell:
 * `$env:MESH_BENCH="notice"; $env:MESH_BENCH_N="2000"; .\gradlew.bat :core:jvmTest --rerun --tests "*MeshBench.notice"`.
 *
 * The air ([Air]) is a disc of radio range with body shadowing in the RSSI, GATT setup that takes time and sometimes
 * fails, and one transmitter per phone; there is no interference between phones, so these are the engine's limits,
 * not the channel's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshBench {
    @Test
    fun smoke() = runTest(timeout = 5.minutes) {
        val b = Bench(this, n = 150, neighbours = 15.0, seed = 3)
        b.warm(60_000)
        val reports = List(2) { b.report(b.randomParticipant()).also { advanceTimeBy(5_000) } }
        val notice = b.notice(b.consoleNode())
        val probes = b.probes(hops = 1..4, perHop = 2, spacingMs = 1_000)
        advanceTimeBy(60_000)
        val text = buildString {
            appendLine(b.header("smoke"))
            appendLine(b.reportTable(reports))
            appendLine(b.coverageLine(notice))
            appendLine(b.probeTable(probes))
        }
        emit("smoke", text)
        b.close()
        assertTrue(reports.all { it.firstStaff != null }, "every report reaches a staff phone")
        assertTrue(b.coverage(notice) > 0.5, "the notice reaches most phones")
    }

    /** (a) An incident report from a random phone: when the first staff phone has it, and when the reporter sees the ACK. */
    @Test
    fun reports() = scenario("reports") { n, neighbours ->
        Bench(this, n, neighbours, air(), staffEvery = staffEvery(), variant = variant()).use { b ->
            b.warm(WARM_MS)
            val before = b.totals()
            val reports = List(12) { b.report(b.randomParticipant()).also { advanceTimeBy(15_000) } }
            advanceTimeBy(300_000)
            b.header("reports") + "\n" + b.reportTable(reports) + "\n" + b.totalsTable(b.totals() - before, 480_000)
        }
    }

    /** (b) A notice from the console: the share of phones that have it over time, live flood and store-and-forward. */
    @Test
    fun notice() = scenario("notice") { n, neighbours ->
        Bench(this, n, neighbours, air(), staffEvery = staffEvery(), variant = variant()).use { b ->
            b.warm(WARM_MS)
            val before = b.totals()
            val notice = b.notice(b.consoleNode())
            val curve = b.coverageCurve(notice, listOf(2_000L, 5_000, 10_000, 30_000, 60_000, 120_000, 300_000, 600_000))
            b.header("notice") + "\n" + curve + "\n" + b.coverageLine(notice) + "\n" + b.totalsTable(b.totals() - before, 600_000)
        }
    }

    /** (c) One PRIVATE between phones 1 to 8 hops apart: delivery, latency and what each costs the crowd. */
    @Test
    fun unicast() = scenario("unicast") { n, neighbours ->
        Bench(this, n, neighbours, air(), staffEvery = staffEvery(), variant = variant()).use { b ->
            b.warm(WARM_MS)
            val before = b.totals()
            val probes = b.probes(hops = 1..8, perHop = 8, spacingMs = 2_000)
            advanceTimeBy(30_000)
            b.header("unicast") + "\n" + b.probeTable(probes) + "\n" + b.totalsTable(b.totals() - before, 30_000L + probes.size * 2_000L)
        }
    }

    /**
     * (d) Mixed load: 20 % of phones share their position with 1 to 3 friends every 30 to 60 s, 5 % chat once a minute
     * with receipts and retries, a report every 30 s with the staff's ACK, one notice; at 1x, 2x and 4x the load
     * (`MESH_BENCH_LOAD`).
     */
    @Test
    fun mixed() = scenario("mixed") { n, neighbours ->
        val load = (System.getenv("MESH_BENCH_LOAD") ?: "1,2,4").split(',').map { it.trim().toDouble() }
        load.joinToString("\n") { k ->
            Bench(this, n, neighbours, air(), staffEvery = staffEvery(), variant = variant()).use { b ->
                b.warm(WARM_MS)
                b.mixed(k, durationMs = 300_000)
            }
        }
    }

    /** The links the policy builds: degree, length, hop progress, and how many phones a TTL of 6 or 7 reaches. */
    @Test
    fun topology() = scenario("topology") { n, neighbours ->
        Bench(this, n, neighbours, air(), staffEvery = staffEvery(), variant = variant()).use { b ->
            b.warm(WARM_MS)
            val first = b.topology()
            advanceTimeBy(300_000)
            b.header("topology") + "\nafter ${WARM_MS / 1000} s:\n" + first + "\nafter ${(WARM_MS + 300_000) / 1000} s:\n" + b.topology() +
                "\n" + b.churnLine()
        }
    }

    /** `MESH_BENCH_STAFF`: one phone in this many is staff (100). */
    private fun staffEvery(): Int = System.getenv("MESH_BENCH_STAFF")?.toInt() ?: 100

    /** `MESH_BENCH_AIR`: `walk` (1 m/s), `iphone` (30 % of links at 182-byte frames), `harsh` (30 % of GATT setups fail). */
    private fun air(): Air {
        val v = (System.getenv("MESH_BENCH_AIR") ?: "").split(',').map { it.trim() }
        return Air(
            walkMps = if ("walk" in v) 1.0 else 0.0,
            smallMtuShare = if ("iphone" in v) 0.3 else 0.0,
            connectFailure = if ("harsh" in v) 0.3 else 0.1,
        )
    }

    private fun scenario(name: String, body: suspend TestScope.(n: Int, neighbours: Double) -> String) = runTest(timeout = 14.minutes) {
        Assume.assumeTrue("set mesh.bench or MESH_BENCH to run it", enabled(name))
        val sizes = (System.getenv("MESH_BENCH_N") ?: "500,1000").split(',').map { it.trim().toInt() }
        val densities = (System.getenv("MESH_BENCH_S") ?: "20").split(',').map { it.trim().toDouble() }
        val out = StringBuilder()
        for (n in sizes) for (s in densities) {
            val started = System.nanoTime()
            out.appendLine(body(n, s))
            out.appendLine("(wall time ${(System.nanoTime() - started) / 1_000_000_000} s)")
            out.appendLine()
        }
        emit(name, out.toString())
    }

    private fun enabled(name: String): Boolean {
        val v = System.getProperty("mesh.bench") ?: System.getenv("MESH_BENCH") ?: return false
        return v == "all" || name in v.split(',').map { it.trim() }
    }

    private fun variant(): String = System.getenv("MESH_BENCH_VARIANT") ?: "baseline"

    private fun emit(name: String, text: String) {
        println(text)
        val dir = File("build/mesh-bench").apply { mkdirs() }
        val air = System.getenv("MESH_BENCH_AIR")?.takeIf { it.isNotBlank() }
        val tag = listOfNotNull(variant(), air, "groups".takeIf { System.getenv("MESH_BENCH_GROUPS") == "1" }).joinToString("-")
        File(dir, "$name-$tag.txt").writeText(text)
    }

    private companion object {
        const val WARM_MS = 120_000L
    }
}

/** Parameters of the simulated radio. */
class Air(
    /** Two phones hear each other within this many metres; the bodies in between show in the RSSI, not here. */
    val range: Double = 12.0,
    /** A scanner hears each advertiser in range once in this period. */
    val sightEveryMs: Long = 2_000,
    /** Body shadowing per pair of phones and noise per sighting, in dB (one standard deviation). */
    val shadowDb: Double = 6.0,
    val noiseDb: Double = 2.0,
    val connectMs: LongRange = 400L..1_500L,
    val connectFailure: Double = 0.1,
    val failMs: LongRange = 1_000L..5_000L,
    /** A frame waits for its link's next connection event, then takes airtime on the phone's only radio. */
    val eventMs: Long = 30,
    val bytesPerMs: Double = 31.25,
    val walkMps: Double = 0.0,
    /** Share of phones whose links carry frames of at most 182 bytes (MTU 185, as on many iPhones). */
    val smallMtuShare: Double = 0.0,
)

/** The crowd's air: where each phone is, who hears whom, links, and every frame put on it. */
class Crowd(private val scope: CoroutineScope, private val clock: Clock, val air: Air, seed: Long) {
    private val random = Random(seed)
    val radios = ArrayList<CrowdRadio>()
    private val byAddress = HashMap<String, CrowdRadio>()
    private val grid = HashMap<Long, ArrayList<CrowdRadio>>()
    private var nextLink = 1
    var width = 0.0
        private set
    var height = 0.0
        private set

    /** By packet type: frames, packets (first fragments) and bytes on the air. */
    val frames = LongArray(256)
    val packets = LongArray(256)
    val bytes = LongArray(256)

    /** The packets a scenario follows, by id: transmissions and bytes. */
    val followed = HashMap<Long, LongArray>()
    var linksUp = 0L
        private set
    var connects = 0L
        private set
    var connectFailures = 0L
        private set

    fun now(): Long = clock.monoMs()

    fun radio(name: String, prefix: Int): CrowdRadio {
        val maxFrame = if (random.nextDouble() < air.smallMtuShare) 182 else 514
        return CrowdRadio(this, radios.size, name, prefix, maxFrame).also {
            radios += it
            byAddress[name] = it
        }
    }

    fun scatter(width: Double, height: Double) {
        this.width = width
        this.height = height
        for (r in radios) {
            r.x = random.nextDouble(width)
            r.y = random.nextDouble(height)
        }
        index()
    }

    private fun cellKey(cx: Long, cy: Long): Long = cx * 1_000_003L + cy

    private fun index() {
        grid.clear()
        for (r in radios) grid.getOrPut(cellKey(floor(r.x / air.range).toLong(), floor(r.y / air.range).toLong())) { ArrayList() }.add(r)
    }

    fun distance(a: CrowdRadio, b: CrowdRadio): Double = hypot(a.x - b.x, a.y - b.y)

    fun inRange(a: CrowdRadio, b: CrowdRadio): Boolean = distance(a, b) <= air.range

    fun neighbours(r: CrowdRadio): List<CrowdRadio> {
        val cx = floor(r.x / air.range).toLong()
        val cy = floor(r.y / air.range).toLong()
        val out = ArrayList<CrowdRadio>()
        for (dx in -1L..1L) for (dy in -1L..1L) {
            val cell = grid[cellKey(cx + dx, cy + dy)] ?: continue
            for (o in cell) if (o !== r && inRange(r, o)) out += o
        }
        return out
    }

    fun start() {
        for (r in radios) r.announce()
        val slices = maxOf(1, (air.sightEveryMs / SLICE_MS).toInt())
        scope.launch {
            var slice = 0
            while (isActive) {
                delay(SLICE_MS)
                var i = slice
                while (i < radios.size) {
                    sightings(radios[i])
                    i += slices
                }
                slice = (slice + 1) % slices
            }
        }
        if (air.walkMps > 0) {
            scope.launch {
                while (isActive) {
                    delay(1_000)
                    walk()
                }
            }
        }
    }

    private fun sightings(scanner: CrowdRadio) {
        if (!scanner.on) return
        for (a in neighbours(scanner)) {
            if (a.on) scanner.events.trySend(RadioEvent.PeerSeen(a.advertisedAddress, a.prefix, a.flags, rssi(a, scanner)))
        }
    }

    /** Log-distance path loss, a shadowing fixed for each pair (the bodies between them) and noise on each sighting. */
    private fun rssi(a: CrowdRadio, b: CrowdRadio): Int {
        val d = maxOf(distance(a, b), 0.5)
        val lo = minOf(a.index, b.index).toLong()
        val hi = maxOf(a.index, b.index).toLong()
        val shadow = gaussian(mix(lo * 1_000_003L + hi)) * air.shadowDb
        val noise = gaussian(random.nextLong()) * air.noiseDb
        return (-45 - 25 * log10(d) + shadow + noise).roundToInt()
    }

    private fun walk() {
        for (r in radios) {
            val angle = random.nextDouble(2 * PI)
            val d = random.nextDouble(air.walkMps)
            r.x = (r.x + d * cos(angle)).coerceIn(0.0, width)
            r.y = (r.y + d * sin(angle)).coerceIn(0.0, height)
        }
        index()
        for (r in radios) for ((id, peer) in r.links.entries.toList()) if (!inRange(r, peer.radio)) r.disconnect(id)
    }

    internal fun connect(from: CrowdRadio, address: String) {
        connects++
        val fails = random.nextDouble() < air.connectFailure
        val wait = random.nextLong(if (fails) air.failMs else air.connectMs)
        scope.launch {
            delay(wait)
            val target = byAddress[address]
            if (fails || target == null || !target.on || !from.on || !inRange(from, target)) {
                connectFailures++
                from.events.trySend(RadioEvent.ConnectFailed(address))
                return@launch
            }
            val a = nextLink++
            val b = nextLink++
            val frame = minOf(from.maxFrame, target.maxFrame)
            from.links[a] = CrowdRadio.Peer(target, b)
            target.links[b] = CrowdRadio.Peer(from, a)
            linksUp++
            from.events.trySend(RadioEvent.LinkUp(a, address, true, frame))
            target.events.trySend(RadioEvent.LinkUp(b, "c:${from.address}", false, frame))
        }
    }

    /** As on a phone, a new prefix comes with a new advertising set under a new address; the old one stops answering. */
    internal fun readvertise(r: CrowdRadio, prefix: Int) {
        scope.launch {
            delay(ADVERTISE_RESTART_MS)
            if (r.advertisedAddress != r.address) byAddress.remove(r.advertisedAddress)
            r.generation++
            r.advertisedAddress = "${r.address}/${r.generation}"
            byAddress[r.advertisedAddress] = r
            r.prefix = prefix
        }
    }

    internal fun airtimeMs(size: Int): Long = 1 + ceil(size / air.bytesPerMs).toLong()

    internal fun eventWaitMs(): Long = random.nextLong(0, air.eventMs + 1)

    internal fun onAir(frame: ByteArray) {
        if (frame.size < 26) return
        val type = frame[1].toInt() and 0xFF
        val flags = frame[3].toInt() and 0xFF
        frames[type]++
        bytes[type] += frame.size.toLong()
        val first = flags and 0x02 == 0 || frame[26 + (if (flags and 0x01 != 0) 8 else 0) + 2].toInt() == 0
        if (first) packets[type]++
        if (followed.isEmpty()) return
        followed[frame.toLong(4)]?.let {
            if (first) it[0]++
            it[1] += frame.size.toLong()
        }
    }

    private companion object {
        const val SLICE_MS = 100L
        const val ADVERTISE_RESTART_MS = 300L

        fun mix(z0: Long): Long {
            var z = z0 + -0x61c8864680b583ebL
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** Close enough to a standard normal: the sum of four uniforms from the 64 bits, centred and scaled. */
        fun gaussian(bits: Long): Double {
            var b = bits
            var sum = 0.0
            repeat(4) {
                sum += (b and 0xFFFF).toDouble() / 65_536.0
                b = b ushr 16
            }
            return (sum - 2.0) * 1.7320508
        }
    }
}

class CrowdRadio internal constructor(
    private val crowd: Crowd,
    val index: Int,
    val address: String,
    prefix: Int,
    val maxFrame: Int,
) : Radio {
    class Peer(val radio: CrowdRadio, val link: Int)

    override val events = Channel<RadioEvent>(Channel.UNLIMITED)
    val links = HashMap<Int, Peer>()
    var flags = 0
    var prefix = prefix
    var x = 0.0
    var y = 0.0
    var on = true
    var advertisedAddress = address
    internal var generation = 0
    private var advertisedPrefix: Int? = null
    private var busyUntil = 0L

    /** How long this phone's radio spent transmitting, in ms. */
    var airMs = 0L
        private set

    fun announce() {
        events.trySend(RadioEvent.Status(RadioStatus(on, on, on, true)))
    }

    override fun connect(address: String, timeoutMs: Long) = crowd.connect(this, address)

    override fun disconnect(link: Int) {
        val peer = links.remove(link) ?: return
        events.trySend(RadioEvent.LinkDown(link))
        if (peer.radio.links.remove(peer.link) != null) peer.radio.events.trySend(RadioEvent.LinkDown(peer.link))
    }

    override suspend fun send(link: Int, frame: ByteArray): Boolean {
        if (links[link] == null) return false
        val now = crowd.now()
        val air = crowd.airtimeMs(frame.size)
        val start = maxOf(now + crowd.eventWaitMs(), busyUntil)
        busyUntil = start + air
        airMs += air
        delay(busyUntil - now)
        val peer = links[link] ?: return false
        crowd.onAir(frame)
        peer.radio.events.trySend(RadioEvent.Frame(peer.link, frame))
        return true
    }

    override fun setAdvertising(flags: Int, prefix: Int) {
        this.flags = flags
        if (advertisedPrefix == prefix) return
        val first = advertisedPrefix == null
        advertisedPrefix = prefix
        if (first) this.prefix = prefix else crowd.readvertise(this, prefix)
    }

    override fun setPowerMode(scan: PowerMode, advertise: PowerMode) = Unit
}

/** Accepts every signature, as the key chain does for genuine ones; staff phones prove their role in HELLO. */
private class BenchTrust(private val random: Random, private val proof: HelloProof?, private val staff: Set<Long>) : MeshTrust {
    override fun checkAck(payload: ByteArray) = Verdict.VALID

    override fun checkResponse(payload: ByteArray) = Verdict.VALID

    override fun checkAnnouncement(payload: ByteArray) = Verdict.VALID

    override fun nonce(): ByteArray = random.nextBytes(Hello.NONCE_SIZE)

    override fun helloProof(peerNonce: ByteArray, nodeId: Long): HelloProof? = proof

    override fun provenRoles(proof: HelloProof, nonce: ByteArray, peerId: Long): Int = if (peerId in staff) NodeFlags.STAFF else 0
}

private class Node(val index: Int, val id: Long, val radio: CrowdRadio, val staff: Boolean) {
    lateinit var engine: MeshEngine
    val outbox = ArrayList<Msg>()
    private val chatSends = ArrayDeque<Long>()
    val answered = HashMap<Long, Long>()
    var retryPending = false

    /** ChatManager's budget: 30 PRIVATE packets a minute, receipts included. */
    fun chatBudget(now: Long): Boolean {
        while (chatSends.isNotEmpty() && now - chatSends.first() >= 60_000) chatSends.removeFirst()
        if (chatSends.size >= 30) return false
        chatSends.addLast(now)
        return true
    }
}

private class Msg(val id: Long, val from: Node, val to: Node, val at: Long) {
    var attempts = 0
    var last = 0L
    var arrived: Long? = null
    var confirmed: Long? = null
}

private class Report(val id8: Long, val reporter: Node, val at: Long, val packetId: Long, val staffHops: Int?) {
    var firstStaff: Long? = null
    var hopsAtStaff: Int? = null
    var staffReached = 0
    var confirmed: Long? = null
    val acks = ArrayList<Long>()
}

private class Probe(val hops: Int, val from: Node, val to: Node, val at: Long, val packetId: Long)

private enum class Kind { REPORT, ACK, NOTICE, POSITION, CHAT, RECEIPT, PROBE }

private class Sent(val kind: Kind, val at: Long, val from: Int, val to: Int)

/** Sums of every engine's counters, and of the air's, at one moment. */
private class Totals(
    val drops: Map<DropReason, Long>,
    val queueFull: Long,
    val relayed: Long,
    val opened: Long,
    val closed: Long,
    val storeAndForward: Long,
    val packets: LongArray,
    val bytes: LongArray,
    val airMs: Long,
    val links: Double,
) {
    operator fun minus(o: Totals) = Totals(
        drops.mapValues { (k, v) -> v - (o.drops[k] ?: 0L) }.filterValues { it != 0L },
        queueFull - o.queueFull, relayed - o.relayed, opened - o.opened, closed - o.closed, storeAndForward - o.storeAndForward,
        LongArray(256) { packets[it] - o.packets[it] }, LongArray(256) { bytes[it] - o.bytes[it] }, airMs - o.airMs, links,
    )
}

/** Engine variants the bench compares, joined with `+`; a new algorithm adds its flag here. `ackdelay` is the staff app's. */
private fun variantConfig(name: String): MeshConfig {
    val flags = name.split('+')
    return MeshConfig(chatFanOut = "fanout" in flags)
}

@OptIn(ExperimentalCoroutinesApi::class)
private class Bench(
    val test: TestScope,
    val n: Int,
    val neighbours: Double,
    val air: Air = Air(),
    val seed: Long = 1,
    val staffEvery: Int = 100,
    val variant: String = "baseline",
) : AutoCloseable {
    /** Everything this crowd runs; cancelled by [close], so the next crowd of a scenario runs alone. */
    val scope = CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
    val clock = TestClock(test.testScheduler)
    val random = Random(seed * 7_919 + 13)
    val crowd = Crowd(scope, clock, air, seed)
    private val area = n * PI * air.range * air.range / neighbours
    private val strip = System.getenv("MESH_BENCH_SHAPE") == "strip"
    val width = if (strip) sqrt(area * 4) else sqrt(area)
    val height = area / width
    val nodes = ArrayList<Node>()
    val byId = HashMap<Long, Node>()
    private val staffIds = HashSet<Long>()
    private val certificate = Certificate(CertRole.STAFF, ByteArray(32), 0, 0xFFFF_FFFFL, 0L, "Staff", ByteArray(64))

    val sent = HashMap<Long, Sent>()
    val arrivals = HashMap<Long, HashMap<Int, Long>>()
    private val reports = HashMap<Long, Report>()
    private val msgs = HashMap<Long, Msg>()
    var positionsTried = 0L
    /** The scenario's phones write positions and chat while this holds. */
    private var writing = true
    var positionsRefused = 0L
    private var warmedAt = 0L
    private var closedAtWarm = 0L

    init {
        val ids = LinkedHashSet<Long>()
        while (ids.size < n) random.nextLong().takeIf { it != 0L && it != ControlChannel.ID }?.let(ids::add)
        ids.forEachIndexed { i, id -> if (i % staffEvery == 0) staffIds += id }
        for ((i, id) in ids.withIndex()) {
            val node = Node(i, id, crowd.radio("N$i", id.nodePrefix()), id in staffIds)
            nodes += node
            byId[id] = node
        }
        crowd.scatter(width, height)
        val config = variantConfig(variant)
        for (node in nodes) {
            val proof = if (node.staff) HelloProof(certificate, ByteArray(64)) else null
            val trust = BenchTrust(Random(seed * 31 + node.index), proof, staffIds)
            node.engine = MeshEngine(
                scope, node.radio, clock, Random(seed * 1_000_003 + node.index), node.id, config, PacketLog(false), null, trust,
            )
            scope.launch {
                node.engine.events.collect {
                    when (it) {
                        is MeshEvent.Received -> received(node, it.packet)
                        is MeshEvent.PeerLinked -> linkRetry(node)
                        else -> Unit
                    }
                }
            }
            node.engine.start()
            if (node.staff) node.engine.setRole(staff = true, anchor = false)
        }
    }

    override fun close() = scope.cancel()

    fun warm(ms: Long) {
        crowd.start()
        test.advanceTimeBy(ms)
        warmedAt = clock.monoMs()
        closedAtWarm = totals().closed
        scope.launch {
            while (isActive) {
                delay(5_000)
                for (node in nodes) retry(node, link = false)
            }
        }
    }

    fun randomParticipant(): Node {
        while (true) nodes[random.nextInt(n)].takeIf { !it.staff }?.let { return it }
    }

    fun consoleNode(): Node = nodes.filter { it.staff }.minBy { hypot(it.radio.x - width / 2, it.radio.y - height / 2) }

    private fun follow(p: Packet, kind: Kind, from: Node, to: Node?) {
        sent[p.id] = Sent(kind, clock.monoMs(), from.index, to?.index ?: -1)
        crowd.followed[p.id] = LongArray(2)
    }

    // --- traffic ---

    fun report(from: Node): Report {
        val incidentId = random.nextBytes(INCIDENT_ID_SIZE)
        val adjacency = adjacency()
        val dist = bfs(adjacency, from.index)
        val nearestStaff = nodes.filter { it.staff && dist[it.index] >= 0 }.minOfOrNull { dist[it.index] }
        val p = from.engine.publishReport(incidentId, random.nextBytes(REPORT_SEALED))
        follow(p, Kind.REPORT, from, null)
        return Report(incidentId.toLong(), from, clock.monoMs(), p.id, nearestStaff).also { reports[it.id8] = it }
    }

    fun notice(from: Node): Long {
        val wall = clock.wallMs() / 1000
        val notice = Notice(
            NoticeTopic.ALERT, NoticeLevel.EVACUATION, 0, from.engine.noticeSeq(NoticeTopic.ALERT) + 1, wall, wall + 3_600, 1, "",
            emptyList(), certificate, ByteArray(64),
        )
        val p = from.engine.publishNotice(notice)!!
        follow(p, Kind.NOTICE, from, null)
        return p.id
    }

    /** One probe (a 55-byte PRIVATE, a position's size) per pair of phones [hops] apart in the link graph now. */
    fun probes(hops: IntRange, perHop: Int, spacingMs: Long): List<Probe> {
        val adjacency = adjacency()
        val pairs = ArrayList<Pair<Int, Pair<Node, Node>>>()
        for (h in hops) {
            var found = 0
            var tries = 0
            while (found < perHop && tries < 200) {
                tries++
                val from = nodes[random.nextInt(n)]
                val dist = bfs(adjacency, from.index)
                val at = nodes.filter { dist[it.index] == h }
                if (at.isEmpty()) continue
                pairs += h to (from to at[random.nextInt(at.size)])
                found++
            }
        }
        return pairs.map { (h, pair) ->
            val (from, to) = pair
            val p = from.engine.unicast(PacketType.PRIVATE, to.id, payload(PROBE, random.nextLong(), POSITION_SIZE))
            follow(p, Kind.PROBE, from, to)
            Probe(h, from, to, clock.monoMs(), p.id).also { test.advanceTimeBy(spacingMs) }
        }
    }

    private fun chat(from: Node, to: Node) {
        val m = Msg(random.nextLong(), from, to, clock.monoMs())
        msgs[m.id] = m
        from.outbox += m
        attempt(m)
    }

    /** As ChatManager: every attempt is a new packet, within the chat budget, and only with a link up. */
    private fun attempt(m: Msg) {
        val now = clock.monoMs()
        if (m.from.engine.state.value.readyLinks == 0 || !m.from.chatBudget(now)) return
        val p = m.from.engine.unicast(PacketType.PRIVATE, m.to.id, payload(TEXT, m.id, TEXT_SIZE))
        follow(p, Kind.CHAT, m.from, m.to)
        m.attempts++
        m.last = now
    }

    private fun retry(node: Node, link: Boolean) {
        if (node.outbox.isEmpty()) return
        val now = clock.monoMs()
        for (m in node.outbox.toList()) {
            if (m.confirmed != null || m.attempts >= 20) continue
            val backoff = backoffMs(m.attempts)
            val wait = if (link) minOf(backoff, 60_000L) else backoff
            if (m.attempts == 0 || now - m.last >= wait) attempt(m)
        }
    }

    private fun linkRetry(node: Node) {
        if (node.outbox.isEmpty() || node.retryPending) return
        node.retryPending = true
        scope.launch {
            delay(1_500)
            node.retryPending = false
            retry(node, link = true)
        }
    }

    /** As ChatManager.sharePosition: one PRIVATE per friend; when none went, the next fix (5 s later) tries again. */
    private fun share(node: Node, friends: List<Node>, everyMs: LongRange) {
        scope.launch {
            delay(random.nextLong(everyMs))
            while (isActive && writing) {
                var tries = 0
                while (writing && !positionRound(node, friends) && tries++ < 12) delay(FIX_MS)
                delay(random.nextLong(everyMs))
            }
        }
    }

    private fun positionRound(node: Node, friends: List<Node>): Boolean {
        if (node.engine.state.value.readyLinks == 0) return false
        var any = false
        for (f in friends) {
            positionsTried++
            val p = node.engine.unicastPosition(f.id, payload(POSITION, 0, POSITION_SIZE))
            if (p == null) positionsRefused++ else {
                follow(p, Kind.POSITION, node, f)
                any = true
            }
        }
        return any
    }

    private fun received(node: Node, p: Packet) {
        val now = clock.monoMs()
        if (p.id in sent) arrivals.getOrPut(p.id) { HashMap() }.putIfAbsent(node.index, now)
        when (p.type) {
            PacketType.INCIDENT_REPORT -> if (node.staff) staffOpens(node, p, now)
            PacketType.INCIDENT_ACK -> IncidentAck.decode(p.payload)?.let { ack ->
                reports[ack.incidentId.toLong()]?.let { r -> if (r.reporter === node && r.confirmed == null) r.confirmed = now }
            }
            PacketType.PRIVATE -> onPrivate(node, p, now)
        }
    }

    /** As IncidentManager: the first staff phone to open a report answers RECEIVED, unless an ACK is already known. */
    private fun staffOpens(node: Node, p: Packet, now: Long) {
        val incidentId = IncidentReportCodec.incidentId(p.payload)
        val r = reports[incidentId.toLong()] ?: return
        if (r.firstStaff == null) {
            r.firstStaff = now
            r.hopsAtStaff = p.hops
        }
        r.staffReached++
        if (node.engine.ackStatus(incidentId) != AckStatus.NONE) return
        scope.launch {
            // staff within 2 hops answer at once; the others wait long enough for that ACK to reach them first
            val near = p.hops <= 2
            val wait = if ("ackdelay" !in variant.split('+') || near) ACK_AFTER_MS else 3_000L + 1_000L * p.hops + random.nextLong(1_000)
            delay(wait)
            if (node.engine.ackStatus(incidentId) != AckStatus.NONE) return@launch
            val signature = ByteArray(IncidentAck.SIGNATURE_SIZE) { 1 }
            val ack = IncidentAck(incidentId, AckStatus.RECEIVED, clock.wallMs() / 1000, "T${node.index}", signature)
            node.engine.publishAck(ack)?.let {
                follow(it, Kind.ACK, node, r.reporter)
                r.acks += it.id
            }
        }
    }

    /** A text is answered with a receipt, each copy again after 10 s, as ChatManager does. */
    private fun onPrivate(node: Node, p: Packet, now: Long) {
        when (p.payload[0].toInt()) {
            TEXT -> {
                val msgId = p.payload.toLong(1)
                msgs[msgId]?.let { if (it.arrived == null) it.arrived = now }
                val last = node.answered[msgId]
                if (last != null && now - last < 10_000) return
                val sender = byId[p.sender] ?: return
                if (!node.chatBudget(now)) return
                node.answered[msgId] = now
                val r = node.engine.unicast(PacketType.PRIVATE, sender.id, payload(RECEIPT, msgId, RECEIPT_SIZE))
                follow(r, Kind.RECEIPT, node, sender)
            }
            RECEIPT -> msgs[p.payload.toLong(1)]?.let { m ->
                if (m.from === node && m.confirmed == null) {
                    m.confirmed = now
                    node.outbox.remove(m)
                }
            }
        }
    }

    // --- scenarios ---

    fun mixed(load: Double, durationMs: Long): String {
        val participants = nodes.filter { !it.staff }
        fun friendOf(node: Node): Node {
            if (random.nextDouble() < NEAR_FRIENDS) {
                val near = participants.filter { it !== node && hypot(it.radio.x - node.radio.x, it.radio.y - node.radio.y) <= 50.0 }
                if (near.isNotEmpty()) return near[random.nextInt(near.size)]
            }
            while (true) participants[random.nextInt(participants.size)].takeIf { it !== node }?.let { return it }
        }
        val sharers = participants.shuffled(random).take((participants.size * 0.2 * load).roundToInt().coerceAtMost(participants.size))
        if (System.getenv("MESH_BENCH_GROUPS") == "1") {
            // groups of 2 to 4 where everyone shares with everyone, together in 70 % of them, as friends at a festival
            val free = sharers.toMutableList()
            while (free.size >= 2) {
                val first = free.removeAt(0)
                val near = random.nextDouble() < NEAR_FRIENDS
                val others = free.filter { !near || hypot(it.radio.x - first.radio.x, it.radio.y - first.radio.y) <= 50.0 }
                    .take(1 + random.nextInt(3))
                free -= others.toSet()
                val group = listOf(first) + others
                for (member in group) if (group.size > 1) share(member, group - member, 30_000L..60_000L)
            }
        } else {
            for (s in sharers) share(s, List(1 + random.nextInt(3)) { friendOf(s) }.distinct(), 30_000L..60_000L)
        }
        val chatters = participants.shuffled(random).take((participants.size * 0.05 * load).roundToInt().coerceAtMost(participants.size))
        for (c in chatters) {
            val friend = friendOf(c)
            scope.launch {
                delay(random.nextLong(60_000))
                while (isActive && writing) {
                    chat(c, friend)
                    delay(random.nextLong(45_000L..75_000L))
                }
            }
        }
        val before = totals()
        val start = clock.monoMs()
        val reportList = ArrayList<Report>()
        var notice = 0L
        val reportEvery = (30_000 / load).toLong()
        var nextReport = start + reportEvery / 2
        var noticeDue = true
        while (clock.monoMs() - start < durationMs) {
            test.advanceTimeBy(1_000)
            val now = clock.monoMs()
            if (now >= nextReport) {
                reportList += report(randomParticipant())
                nextReport += reportEvery
            }
            if (noticeDue && now - start >= 60_000) {
                notice = notice(consoleNode())
                noticeDue = false
            }
        }
        // what was written last gets a minute more to arrive; only retries go out meanwhile
        val end = clock.monoMs()
        writing = false
        test.advanceTimeBy(60_000)
        val delta = totals() - before
        return buildString {
            val title = "mixed load x$load: ${sharers.size} sharing positions, ${chatters.size} chatting"
            appendLine(header("$title, a report every ${reportEvery / 1000} s"))
            appendLine(classTable(start, end))
            appendLine(reportTable(reportList))
            appendLine(coverageLine(notice))
            val refused = pct(positionsRefused, positionsTried)
            appendLine("positions: $positionsTried tried, $positionsRefused refused by PositionBudget ($refused)")
            append(totalsTable(delta, durationMs + 60_000))
        }
    }

    // --- measurements ---

    fun totals(): Totals {
        val drops = HashMap<DropReason, Long>()
        var queueFull = 0L
        var relayed = 0L
        var opened = 0L
        var closed = 0L
        var sf = 0L
        var links = 0L
        for (node in nodes) {
            val s = node.engine.state.value
            for ((k, v) in s.counters.drops) drops[k] = (drops[k] ?: 0L) + v
            queueFull += s.queueFull
            relayed += s.relayed
            opened += s.counters.linksOpened
            closed += s.counters.linksClosed
            sf += s.counters.storeAndForward
            links += s.readyLinks
        }
        return Totals(
            drops, queueFull, relayed, opened, closed, sf, crowd.packets.copyOf(), crowd.bytes.copyOf(), crowd.radios.sumOf { it.airMs },
            links.toDouble() / n,
        )
    }

    fun header(name: String): String =
        "== $name: $n phones over ${width.roundToInt()} x ${height.roundToInt()} m, " +
            "${fmt(neighbours, 0)} in range (${air.range.roundToInt()} m), " +
            "${staffIds.size} staff, variant $variant; ${fmt(totals().links, 1)} ready links per phone"

    fun adjacency(): Array<IntArray> {
        val sets = Array(n) { HashSet<Int>() }
        for (node in nodes) for (l in node.engine.state.value.links) {
            if (l.peerId == 0L) continue
            val o = byId[l.peerId] ?: continue
            sets[node.index] += o.index
            sets[o.index] += node.index
        }
        return Array(n) { sets[it].toIntArray() }
    }

    fun bfs(adjacency: Array<IntArray>, from: Int): IntArray {
        val dist = IntArray(n) { -1 }
        dist[from] = 0
        val queue = ArrayDeque<Int>()
        queue += from
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            for (w in adjacency[v]) if (dist[w] < 0) {
                dist[w] = dist[v] + 1
                queue += w
            }
        }
        return dist
    }

    fun topology(): String {
        val adjacency = adjacency()
        val degrees = adjacency.map { it.size }
        val lengths = ArrayList<Double>()
        for (i in 0 until n) for (j in adjacency[i]) if (i < j) lengths += crowd.distance(nodes[i].radio, nodes[j].radio)
        val seen = BooleanArray(n)
        var giant = 0
        for (i in 0 until n) if (!seen[i]) {
            val dist = bfs(adjacency, i)
            var size = 0
            for (k in 0 until n) if (dist[k] >= 0) {
                seen[k] = true
                size++
            }
            giant = maxOf(giant, size)
        }
        val within = Array(11) { ArrayList<Int>() }
        val progress = Array(11) { ArrayList<Double>() }
        var eccentricity = 0
        // sources away from the edge, so the counts are those of a crowd that goes on around them
        val inner = nodes.filter { abs(it.radio.x - width / 2) < width / 4 && abs(it.radio.y - height / 2) < height / 4 }
        repeat(30) {
            val src = inner[random.nextInt(inner.size)]
            val dist = bfs(adjacency, src.index)
            for (h in 1..10) within[h] += dist.count { it in 1..h }
            for (k in 0 until n) if (dist[k] in 1..10) progress[dist[k]] += crowd.distance(src.radio, nodes[k].radio)
            eccentricity = maxOf(eccentricity, dist.max())
        }
        val sorted = lengths.sorted()
        return buildString {
            appendLine(
                "degree mean ${fmt(degrees.average(), 2)} (0: ${degrees.count { it == 0 }}, <=2: ${degrees.count { it <= 2 }}), " +
                    "giant component ${pct(giant.toLong(), n.toLong())}, max hops from a central phone $eccentricity",
            )
            appendLine(
                "link length mean ${fmt(lengths.average(), 1)} m, p50 ${fmt(sorted[sorted.size / 2], 1)} m, " +
                    "p90 ${fmt(sorted[(sorted.size * 9) / 10], 1)} m (range ${air.range} m)",
            )
            appendLine("hops            " + (1..10).joinToString("") { "%7d".format(it) })
            appendLine("phones within   " + (1..10).joinToString("") { "%7d".format(within[it].average().roundToInt()) })
            val distances = (1..10).joinToString("") {
                "%7.1f".format(Locale.ROOT, progress[it].average().takeIf { v -> !v.isNaN() } ?: 0.0)
            }
            append("mean distance m $distances")
        }
    }

    fun churnLine(): String {
        val t = totals()
        val minutes = (clock.monoMs() - warmedAt) / 60_000.0
        val perPhone = fmt((t.closed - closedAtWarm) / n.toDouble() / minutes, 2)
        return "links opened ${t.opened}, closed ${t.closed} since the start; $perPhone closed per phone per minute after the warm-up"
    }

    fun coverage(packetId: Long): Double = (arrivals[packetId]?.size ?: 0) / (n - 1).toDouble()

    fun coverageCurve(packetId: Long, at: List<Long>): String {
        val start = sent.getValue(packetId).at
        val rows = ArrayList<String>()
        for (t in at) {
            val wait = start + t - clock.monoMs()
            if (wait > 0) test.advanceTimeBy(wait)
            rows += "${t / 1000} s: ${pct((arrivals[packetId]?.size ?: 0).toLong(), (n - 1).toLong())}"
        }
        return "notice coverage  " + rows.joinToString("  ")
    }

    fun coverageLine(packetId: Long): String {
        if (packetId == 0L) return "notice: none"
        val start = sent.getValue(packetId).at
        val times = arrivals[packetId]?.values?.map { it - start }.orEmpty()
        val tx = crowd.followed[packetId]?.get(0) ?: 0L
        return "notice: ${pct(times.size.toLong(), (n - 1).toLong())} of phones, " +
            "p50 ${sec(times.quantile(0.5))}, p95 ${sec(times.quantile(0.95))}, " +
            "$tx transmissions (${fmt(tx.toDouble() / maxOf(1, times.size), 2)} per phone reached)"
    }

    fun reportTable(list: List<Report>): String {
        if (list.isEmpty()) return "reports: none"
        val toStaff = list.mapNotNull { r -> r.firstStaff?.let { it - r.at } }
        val confirmed = list.mapNotNull { r -> r.confirmed?.let { it - r.at } }
        val tx = list.map { crowd.followed[it.packetId]?.get(0) ?: 0L }
        val ackTx = list.flatMap { r -> r.acks.map { crowd.followed[it]?.get(0) ?: 0L } }
        val staffShare = list.map { it.staffReached.toDouble() / staffIds.size }
        return buildString {
            appendLine(
                "reports ${list.size}: at a staff phone ${toStaff.size} (within 10 s ${toStaff.count { it <= 10_000 }}, " +
                    "60 s ${toStaff.count { it <= 60_000 }}), p50 ${sec(toStaff.quantile(0.5))}, " +
                    "p95 ${sec(toStaff.quantile(0.95))}, max ${sec(toStaff.maxOrNull())}",
            )
            appendLine(
                "  ACK back at the reporter ${confirmed.size} (within 30 s ${confirmed.count { it <= 30_000 }}), " +
                    "p50 ${sec(confirmed.quantile(0.5))}, p95 ${sec(confirmed.quantile(0.95))}; ${ackTx.size} ACKs written",
            )
            appendLine(
                "  nearest staff (graph hops) ${list.map { it.staffHops ?: -1 }.sorted()}, " +
                    "hops at first staff ${list.map { it.hopsAtStaff ?: -1 }.sorted()}",
            )
            append(
                "  staff phones holding it at the end ${pct((staffShare.average() * 1000).roundToInt().toLong(), 1000)}; " +
                    "transmissions per report ${fmt(tx.average(), 0)}, per ACK ${fmt(ackTx.average(), 0)}",
            )
        }
    }

    fun probeTable(list: List<Probe>): String = buildString {
        appendLine("hops  sent  delivered  p50     p95     transmissions per probe")
        for ((h, group) in list.groupBy { it.hops }.toSortedMap()) {
            val times = group.mapNotNull { p -> arrivals[p.packetId]?.get(p.to.index)?.let { it - p.at } }
            val tx = group.map { crowd.followed[it.packetId]?.get(0) ?: 0L }
            appendLine(
                "%4d %5d %10d  %-7s %-7s %s".format(
                    Locale.ROOT, h, group.size, times.size, sec(times.quantile(0.5)), sec(times.quantile(0.95)), fmt(tx.average(), 0),
                ),
            )
        }
    }.trimEnd()

    /** For each kind of packet the scenario wrote: how many, how many arrived where they should, how fast, at what cost. */
    private fun classTable(from: Long, to: Long): String = buildString {
        appendLine("class      written  arrived   ratio   p50     p95     tx/written  tx/arrived")
        for (kind in listOf(Kind.POSITION, Kind.CHAT, Kind.RECEIPT, Kind.REPORT, Kind.ACK)) {
            val ids = sent.filter { (_, s) -> s.kind == kind && s.at in from..to }.keys
            if (ids.isEmpty()) continue
            var arrived = 0
            val times = ArrayList<Long>()
            var tx = 0L
            for (id in ids) {
                val s = sent.getValue(id)
                tx += crowd.followed[id]?.get(0) ?: 0L
                val at = if (s.to >= 0) arrivals[id]?.get(s.to) else arrivals[id]?.filterKeys { nodes[it].staff }?.values?.minOrNull()
                if (at != null) {
                    arrived++
                    times += at - s.at
                }
            }
            appendLine(
                "%-9s %8d %8d %7s   %-7s %-7s %10s %11s".format(
                    Locale.ROOT, kind.name.lowercase(), ids.size, arrived, pct(arrived.toLong(), ids.size.toLong()),
                    sec(times.quantile(0.5)),
                    sec(times.quantile(0.95)), fmt(tx.toDouble() / ids.size, 0), fmt(tx.toDouble() / maxOf(1, arrived), 0),
                ),
            )
        }
        val chat = msgs.values.filter { it.at in from..to }
        if (chat.isNotEmpty()) {
            val arrived = chat.mapNotNull { m -> m.arrived?.let { it - m.at } }
            val confirmed = chat.mapNotNull { m -> m.confirmed?.let { it - m.at } }
            append(
                "chat messages ${chat.size}: arrived ${arrived.size} " +
                    "(p50 ${sec(arrived.quantile(0.5))}, p95 ${sec(arrived.quantile(0.95))}), " +
                    "confirmed ${confirmed.size} (p50 ${sec(confirmed.quantile(0.5))}), " +
                    "attempts per message ${fmt(chat.map { it.attempts }.average(), 2)}",
            )
        }
    }.trimEnd()

    fun totalsTable(t: Totals, windowMs: Long): String = buildString {
        val seconds = windowMs / 1000.0
        val types = (0 until 256).filter { t.packets[it] > 0 }.sortedByDescending { t.packets[it] }
        appendLine(
            "air over ${windowMs / 1000} s: " + types.joinToString(", ") { "${PacketType.name(it)} ${t.packets[it]}" } +
                "; ${fmt(t.packets.sum() / seconds / n, 2)} packets/s and ${fmt(t.bytes.sum() / seconds / n, 0)} B/s per phone",
        )
        appendLine(
            "  per link direction ${fmt(t.packets.sum() / seconds / maxOf(1.0, t.links * n), 2)} packets/s (cap 2.8); " +
                "radio busy ${pct(t.airMs, (windowMs * n))} of the time on average",
        )
        appendLine(
            "  drops " + dropsText(t) +
                "; queue full ${t.queueFull}; store-and-forward replies ${t.storeAndForward}",
        )
        val churn = fmt(t.closed / n.toDouble() / (windowMs / 60_000.0), 2)
        append("  links opened ${t.opened}, closed ${t.closed} ($churn per phone per minute)")
    }

    private companion object {
        const val REPORT_SEALED = 380
        const val POSITION_SIZE = 55
        const val TEXT_SIZE = 100
        const val RECEIPT_SIZE = 57
        const val POSITION = 1
        const val TEXT = 2
        const val RECEIPT = 3
        const val PROBE = 4
        const val ACK_AFTER_MS = 200L
        const val FIX_MS = 5_000L
        const val NEAR_FRIENDS = 0.7

        fun payload(kind: Int, ref: Long, size: Int): ByteArray = ByteArray(size).also {
            it[0] = kind.toByte()
            for (i in 0 until 8) it[1 + i] = (ref ushr (56 - 8 * i)).toByte()
        }

        fun backoffMs(attempts: Int): Long = when {
            attempts <= 0 -> 0
            attempts == 1 -> 15_000
            attempts == 2 -> 30_000
            attempts == 3 -> 60_000
            attempts == 4 -> 120_000
            else -> 300_000
        }
    }
}

private fun dropsText(t: Totals): String =
    if (t.drops.isEmpty()) "none" else t.drops.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.label} ${it.value}" }

private fun List<Long>.quantile(p: Double): Long? = if (isEmpty()) null else sorted()[((size - 1) * p).roundToInt()]

private fun fmt(v: Double, decimals: Int): String = if (v.isNaN()) "-" else "%.${decimals}f".format(Locale.ROOT, v)

private fun sec(ms: Long?): String = ms?.let { fmt(it / 1000.0, 1) + " s" } ?: "-"

private fun pct(part: Long, whole: Long): String = if (whole == 0L) "-" else fmt(100.0 * part / whole, 1) + " %"
