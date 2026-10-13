package ro.safetyplease.core.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Path
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeTemplates
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.mesh.BatteryReading
import ro.safetyplease.core.mesh.PowerMode
import ro.safetyplease.core.mesh.PowerPlan
import ro.safetyplease.core.mesh.RadioEvent
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import ro.safetyplease.core.safety.SafetyNotice
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.Venue

/**
 * A phone for [PingUpSession] in tests: files in a temporary folder, the app's own assets, a vault in memory, and a
 * radio and notifications that only remember what they were asked. [assets] picks the build's assets: debug ones
 * carry the demo staff keys.
 */
class FakePlatform(
    val dir: TempDir,
    override val vault: MemoryVault = MemoryVault(),
    private val assets: String = "app/src/debug/assets",
) : AppPlatform {
    override val crypto: Crypto = testCrypto
    override val clock = ManualClock()
    override val filesDir: Path = dir.root / "files"
    override val cacheDir: Path = dir.root / "cache"
    override val debug = false
    override val location = MutableStateFlow<GeoFix?>(null)

    /** What the last [shareLocation] asked for: the slowest pace a running share allows, null with none. */
    var shareIntervalMs: Long? = null

    val radios = mutableListOf<FakeRadio>()
    val shown = FakeNotifications()
    var batteryListener: ((BatteryReading) -> Unit)? = null
    val downloads = mutableMapOf<String, ByteArray>()
    var restarts = 0
    var wipes = 0

    override fun language() = "ro"

    override fun asset(name: String): String {
        val own = repoFile("$assets/$name")
        return (if (exists(own)) own else repoFile("app/src/main/assets/$name")).readText()
    }

    override fun assets(folder: String): Map<String, String> {
        val dir = repoFile("app/src/main/assets/$folder")
        val fs = okio.FileSystem.SYSTEM
        return if (fs.exists(dir)) fs.list(dir).associate { it.name to fs.read(it) { readUtf8() } } else emptyMap()
    }

    private fun exists(path: Path) = okio.FileSystem.SYSTEM.exists(path)

    override fun log(message: String) = Unit

    override fun radio(scope: CoroutineScope, nodeId: Long): MeshRadio = FakeRadio().also { radios += it }

    override fun notifications(venue: Venue, exercise: Boolean): Notifications = shown

    override fun watchBattery(onReading: (BatteryReading) -> Unit) {
        batteryListener = onReading
        onReading(BatteryReading(80, charging = false, temperatureC = 30))
    }

    override suspend fun sampleMobileNetwork(atMs: Long, cell: String) = NetworkSample(at = atMs, grid = cell)

    override fun shareLocation(maxIntervalMs: Long?) {
        shareIntervalMs = maxIntervalMs
    }

    override fun noticeTitle(notice: Notice) = "Anunț ${notice.topic}"

    override fun noticeBody(templates: NoticeTemplates, venue: Venue, notice: Notice) = notice.params.joinToString(" · ")

    override suspend fun download(url: String, limit: Long): ByteArray? = downloads[url]?.let { it.copyOf(minOf(it.size.toLong(), limit).toInt()) }

    override fun restart() {
        restarts++
    }

    override fun wipe() {
        wipes++
    }
}

class ManualClock(var wall: Long = 1_760_000_000_000L, var mono: Long = 0) : Clock {
    override fun wallMs() = wall
    override fun monoMs() = mono
}

class MemoryVault : Vault {
    val entries = mutableMapOf<String, ByteArray>()

    /** Entries that exist but can't be opened any more, as after the key store lost its key. */
    val sealed = mutableSetOf<String>()
    val setAside = mutableListOf<String>()

    override fun load(name: String): ByteArray? = if (name in sealed) null else entries[name]?.copyOf()

    override fun save(name: String, secret: ByteArray) {
        entries[name] = secret.copyOf()
        sealed -= name
    }

    override fun delete(name: String) {
        entries -= name
        sealed -= name
    }

    override fun exists(name: String) = name in entries

    override fun setAside(name: String) {
        setAside += name
        entries.remove(name)?.let { entries["$name.lost"] = it }
        sealed -= name
    }
}

/** Remembers what the session asked of it, from whichever thread. */
class FakeRadio : MeshRadio {
    private val lock = SynchronizedObject()
    private val calls = mutableListOf<String>()
    private val plans = mutableListOf<PowerPlan>()

    override val events: ReceiveChannel<RadioEvent> = Channel(Channel.UNLIMITED)

    fun calls(): List<String> = synchronized(lock) { calls.toList() }

    fun plans(): List<PowerPlan> = synchronized(lock) { plans.toList() }

    override fun start() = synchronized(lock) { calls += "start" }

    override fun stop() = synchronized(lock) { calls += "stop" }

    override fun retry() = synchronized(lock) { calls += "retry" }

    override fun setPower(plan: PowerPlan) = synchronized(lock) {
        calls += "power"
        plans += plan
    }

    override fun connect(address: String, timeoutMs: Long) = Unit

    override fun disconnect(link: Int) = Unit

    override suspend fun send(link: Int, frame: ByteArray) = false

    override fun setAdvertising(flags: Int, prefix: Int) = Unit

    override fun setPowerMode(scan: PowerMode, advertise: PowerMode) = Unit
}

class FakeNotifications : Notifications {
    val posted = mutableListOf<String>()

    override fun chatMessage(message: ChatMessage, senderName: String) {
        posted += "chat ${message.conversation} $senderName"
    }

    override fun cancelConversation(conversation: String) {
        posted += "read $conversation"
    }

    override fun staffAlert(post: AlertPost) {
        posted += "alert ${post.incident.incidentId}"
    }

    override fun cancelStaffAlert(incidentId: String) {
        posted += "cancel $incidentId"
    }

    override fun reportUpdate(report: MyReport, nowMs: Long) {
        posted += "report ${report.incidentId}"
    }

    override fun reportResponse(report: MyReport) {
        posted += "response ${report.incidentId}"
    }

    override fun notice(notice: Notice, title: String, text: String) {
        posted += "notice $title"
    }

    override fun shiftEnded() {
        posted += "shift ended"
    }

    override fun shareReminder(people: Int) {
        posted += "sharing $people"
    }

    override fun safety(notice: SafetyNotice) {
        posted += "safety $notice"
    }
}
