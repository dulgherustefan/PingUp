package ro.safetyplease.app

import android.app.ActivityManager
import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.io.File
import java.security.SecureRandom
import kotlin.random.asKotlinRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ro.safetyplease.app.platform.ble.BleRadio
import ro.safetyplease.app.platform.keystore.KeyVault
import ro.safetyplease.app.platform.location.LocationSource
import ro.safetyplease.app.platform.network.MobileNetwork
import ro.safetyplease.app.service.Notifier
import ro.safetyplease.app.text.EscalationReminder
import ro.safetyplease.app.text.nextEscalation
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.control.ConsoleLog
import ro.safetyplease.core.control.ConsoleRecorder
import ro.safetyplease.core.control.HeardPeer
import ro.safetyplease.core.control.PeerBook
import ro.safetyplease.core.control.ReporterRecords
import ro.safetyplease.core.crypto.CertStatus
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Crypto
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.QrCodes
import ro.safetyplease.core.crypto.Revocations
import ro.safetyplease.core.crypto.ShiftCard
import ro.safetyplease.core.crypto.SodiumCrypto
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.data.ChatStore
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.incidents.AlertPlanner
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.mesh.CachedIncidents
import ro.safetyplease.core.mesh.MeshConfig
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.PacketLog
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.metrics.MeshMetrics
import ro.safetyplease.core.metrics.MetricsLog
import ro.safetyplease.core.metrics.NetworkLog
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.parse

object AndroidClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monoMs(): Long = SystemClock.elapsedRealtime()
}

/** Lives as long as the process: identity, stores, the mesh engine, chat and incident managers. */
class AppContainer(private val app: Application) {
    val clock: Clock = AndroidClock
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One thread for mesh, radio, chat and incidents, so network state needs no locks. */
    val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val crypto: Crypto = SodiumCrypto(LazySodiumAndroid(SodiumAndroid()))
    private val vault = KeyVault(app)
    val identity: Identity = loadIdentity()

    private val dataDir = File(app.filesDir, "data")
    val settings = JsonStore(File(dataDir, "settings.json"), Settings.serializer(), Settings(), ioScope)
    val friends = JsonStore(File(dataDir, "friends.json"), ListSerializer(Friend.serializer()), emptyList(), ioScope)
    val groups = JsonStore(File(dataDir, "groups.json"), ListSerializer(Group.serializer()), emptyList(), ioScope)
    val chatStore = ChatStore(dataDir, ioScope)
    val incidentStore = JsonStore(File(dataDir, "incidents.json"), IncidentData.serializer(), IncidentData(), ioScope)

    val venue: Venue = Venue.parse(asset("venue.json"), AppLocale.language(app))
    val grid = SiteGrid.of(venue.bounds)

    /** The control console's shift log; written only while this phone is the console. */
    val consoleLog = JsonStore(
        File(dataDir, "console_log.json"), ConsoleLog.serializer(), ConsoleLog(), ioScope, ConsoleLog.SCHEMA_VERSION,
    )
    val console = ConsoleRecorder(consoleLog, grid, clock) { id -> incidentStore.value.staff.firstOrNull { it.incidentId == id } }

    /** When the console last heard each phone; kept while this phone is the console. */
    val heard = MutableStateFlow<Map<Int, HeardPeer>>(emptyMap())

    /** The console's last shared log, in the cache; the share sheet reads the files from here. */
    val exportDir = File(app.cacheDir, "export")

    /** The pilot's measurements, kept on this phone until someone exports them; none names the phone. */
    val metricsLog = JsonStore(File(dataDir, "metrics.json"), MetricsLog.serializer(), MetricsLog(), ioScope, MetricsLog.SCHEMA_VERSION)
    val networkLog = JsonStore(File(dataDir, "network_log.json"), NetworkLog.serializer(), NetworkLog(), ioScope, NetworkLog.SCHEMA_VERSION)

    /** The reporters' metrics given to the console, to join with its log. */
    val reporterMetrics = JsonStore(
        File(dataDir, "reporter_metrics.json"), ReporterRecords.serializer(), ReporterRecords(), ioScope, ReporterRecords.SCHEMA_VERSION,
    )
    private val meshMetrics = MeshMetrics(metricsLog, clock)
    private val mobileNetwork = MobileNetwork(app)

    val keyChain: KeyChain
    val staffCrypto: StaffCrypto

    init {
        val json = Json.parseToJsonElement(asset("staff_public.json")).jsonObject
        fun hex(name: String) = json.getValue(name).jsonPrimitive.content.hexToBytes()
        fun certificates(name: String) =
            json[name]?.jsonArray.orEmpty().mapNotNull { Certificate.decode(it.jsonPrimitive.content.hexToBytes()) }
        val public = StaffPublicKeys(hex("box"), hex("sign"))
        val root = json["root"]?.jsonPrimitive?.content?.hexToBytes()
        keyChain = KeyChain(crypto, clock, public.sign, root, certificates("chain"), certificates("reportKeys"))
        staffCrypto = StaffCrypto(crypto, public, keyChain)
    }

    @Volatile
    var staffSecret: StaffSecretKeys? = loadStaffSecret()
        private set

    val log = PacketLog(BuildConfig.DEBUG).apply { sink = { Log.d(TAG, it) } }
    val radio = BleRadio(app, meshScope, identity.nodeId) { Log.i(TAG, it) }

    /** The reports and ACKs this phone carries for store-and-forward, so a restart doesn't lose them. */
    private val meshCache = JsonStore(File(dataDir, "mesh_cache.json"), CachedIncidents.serializer(), CachedIncidents(), ioScope)

    val engine = MeshEngine(
        meshScope, radio, clock, SecureRandom().asKotlinRandom(), identity.nodeId,
        MeshConfig(nicknameInHello = BuildConfig.DEBUG, allowTestPackets = BuildConfig.DEBUG),
        log, meshCache,
        keyChain,
    )

    val notifier = Notifier(app, venue)
    val location = LocationSource(app)

    /** Until when (monotonic) scanning and advertising stay at LOW_LATENCY after an incident is sent. */
    val boostUntil = MutableStateFlow(0L)

    /** Until when (wall time) the console's alerts stay silent: "Silențios 10 min". */
    val mutedUntil = MutableStateFlow(0L)

    /** An activity of the app is started. */
    val foreground = MutableStateFlow(false)

    @Volatile
    var openConversation: String? = null

    val chat = ChatManager(
        meshScope, engine, crypto, identity, friends, groups, chatStore, clock, SecureRandom().asKotlinRandom(),
        myNickname = { settings.value.nickname },
    ) { message, sender ->
        if (!foreground.value || openConversation != message.conversation) notifier.chatMessage(message, sender)
    }

    val incidents = IncidentManager(
        meshScope, engine, crypto, staffCrypto, incidentStore, clock,
        staffSecret = { staffSecret },
        teamName = { settings.value.teamName },
        onStaffAlertCleared = { notifier.cancelStaffAlert(it) },
        onReportUpdate = { if (!foreground.value) notifier.reportUpdate(it, clock.wallMs()) },
        onReportSent = { boostUntil.value = clock.monoMs() + PowerPolicy.BOOST_MS },
        onAck = { ack, own, hops -> if (settings.value.role == Role.CONTROL) console.sawAck(ack, own, hops) },
        staffCap = { if (settings.value.role == Role.CONTROL) IncidentManager.MAX_STAFF_CONSOLE else IncidentManager.MAX_STAFF },
    )

    private val revocations = Revocations(
        meshScope, engine, keyChain,
        JsonStore(File(dataDir, "revocations.json"), ListSerializer(String.serializer()), emptyList(), ioScope),
        resends = { settings.value.role == Role.CONTROL },
    ) {
        // a revoked shift signs nothing any more; the phone goes back to being a participant's
        if (keyChain.credential?.let { keyChain.status(it.certificate) == CertStatus.REVOKED } == true) leaveStaff()
    }

    /** This phone holds a shift certificate the chain accepts now. */
    val verified: Boolean get() = keyChain.credential?.let { keyChain.status(it.certificate) == CertStatus.VALID } == true

    init {
        meshScope.launch {
            engine.start()
            chat.start()
            incidents.start()
            revocations.start()
            settings.state.collect { s ->
                engine.setRole(staff = s.role.hasStaffKeys, anchor = s.role == Role.ANCHOR)
                engine.setNickname(s.nickname)
                engine.setIgnoredPrefixes(s.ignoredPrefixes.toSet())
            }
        }
        meshScope.launch { remindUnconfirmed() }
        meshScope.launch { routeAlerts() }
        meshScope.launch {
            while (true) {
                delay(MeshMetrics.INTERVAL_MS)
                meshMetrics.snapshot(engine.state.value, settings.value.role)
            }
        }
        ioScope.launch { logMobileNetwork() }
        meshScope.launch {
            settings.state.map { it.role == Role.CONTROL }.distinctUntilChanged().collectLatest { control ->
                if (control) {
                    watchAsConsole()
                } else {
                    heard.value = emptyMap()
                }
            }
        }
    }

    /**
     * While this phone is the console: logs every report it opens and remembers when it last heard each phone.
     * Files a past export left in the cache go when the console starts; the log keeps everything they held.
     */
    private suspend fun watchAsConsole() = coroutineScope {
        launch(Dispatchers.IO) { exportDir.deleteRecursively() }
        launch { incidentStore.state.collect { console.sawIncidents(it.staff) } }
        launch { engine.state.collect { heard.value = PeerBook.update(heard.value, it, clock.wallMs(), clock.monoMs()) } }
    }

    /**
     * The staff alerts, from what each incident's route is now: they change with the store, the settings and the
     * mute, and in time, as an alert repeats or widens.
     */
    private suspend fun routeAlerts() {
        val planner = AlertPlanner(clock.wallMs())
        combine(incidentStore.state, settings.state, mutedUntil) { data, s, muted -> Triple(data, s, muted) }.collectLatest { (data, s, muted) ->
            if (!s.role.hasStaffKeys) return@collectLatest
            val me = Routing.profile(s)
            val own = data.mine.mapTo(HashSet()) { it.incidentId }
            while (true) {
                val now = clock.wallMs()
                val plan = planner.plan(me, data.staff, own, now, muted)
                plan.stop.forEach(notifier::cancelStaffAlert)
                plan.post.forEach(notifier::staffAlert)
                val next = plan.nextAt ?: break
                delay(next - now)
            }
        }
    }

    /** While staff keep the log on: the mobile network every 2 minutes, on the grid square of a fresh position. */
    private suspend fun logMobileNetwork() {
        settings.state.map { it.networkLog && it.role != Role.PARTICIPANT }.distinctUntilChanged().collectLatest { on ->
            if (!on) return@collectLatest
            while (true) {
                val now = clock.wallMs()
                val cell = location.fix.value?.takeIf { it.isFresh(now) }?.let { grid.cellAt(it.lat, it.lon) }.orEmpty()
                val sample = mobileNetwork.sample(now, cell)
                networkLog.update { it.with(sample) }
                delay(NetworkLog.INTERVAL_MS)
            }
        }
    }

    /** When an urgent report of yours has waited too long for a person on staff, tells you so from the background. */
    private suspend fun remindUnconfirmed() {
        val reminder = EscalationReminder(clock.wallMs())
        combine(incidentStore.state, foreground) { data, fg -> data.mine to fg }.collectLatest { (mine, fg) ->
            while (true) {
                val now = clock.wallMs()
                reminder.due(mine, now, fg).forEach { notifier.reportUpdate(it, now) }
                val next = nextEscalation(mine, now) ?: break
                delay(next - now)
            }
        }
    }

    /** False if the QR belongs to another event. Anchors don't keep the secret keys, since they sit unattended. */
    fun activateStaff(card: StaffCard): Boolean {
        val secret = staffCrypto.secretFromSeeds(card.boxSeed, card.signSeed) ?: return false
        vault.delete(VAULT_SHIFT)
        keyChain.credential = null
        if (card.role == StaffRole.ANCHOR) {
            vault.delete(VAULT_STAFF)
            staffSecret = null
            settings.update { it.copy(role = Role.ANCHOR, teamName = card.teamName, anchorZone = card.zone, staffZone = "", roleLead = false) }
        } else {
            vault.save(VAULT_STAFF, card.boxSeed + card.signSeed)
            staffSecret = secret
            val role = if (card.role == StaffRole.CONTROL) Role.CONTROL else Role.STAFF
            settings.update { it.copy(role = role, teamName = card.teamName, anchorZone = "", staffZone = card.zone) }
            incidents.reprocessCached()
        }
        return true
    }

    /**
     * An SPS2 code: VALID once active, otherwise why the chain refuses its certificate. An anchor keeps only its shift
     * key, which proves its role and opens nothing; a team without the day's report key relays reports it can't open.
     */
    fun activateShift(card: ShiftCard, text: String): CertStatus {
        val status = keyChain.status(card.certificate)
        if (status != CertStatus.VALID) return status
        val (credential, secret) = staffCrypto.credentialFrom(card) ?: return CertStatus.UNKNOWN
        vault.delete(VAULT_STAFF)
        vault.save(VAULT_SHIFT, text.trim().toByteArray())
        keyChain.credential = credential
        card.reportKey?.let(keyChain::addReportKey)
        staffSecret = secret
        val role = when (card.role) {
            StaffRole.ANCHOR -> Role.ANCHOR
            StaffRole.CONTROL -> Role.CONTROL
            StaffRole.STAFF -> Role.STAFF
        }
        settings.update {
            val anchor = role == Role.ANCHOR
            it.copy(
                role = role, teamName = card.teamName, anchorZone = if (anchor) card.zone else "", staffZone = if (anchor) "" else card.zone,
                roleLead = it.roleLead && !anchor,
            )
        }
        if (secret != null) incidents.reprocessCached()
        return CertStatus.VALID
    }

    /** An SPR1 code: the certificate ids it newly revokes, or null if this event's keys didn't sign it. */
    fun applyRevocation(payload: ByteArray): List<Long>? = revocations.add(payload)

    fun leaveStaff() {
        vault.delete(VAULT_STAFF)
        vault.delete(VAULT_SHIFT)
        keyChain.credential = null
        staffSecret = null
        mutedUntil.value = 0
        settings.update { it.copy(role = Role.PARTICIPANT, teamName = "", anchorZone = "", staffZone = "", roleLead = false) }
    }

    fun wipeAllData() {
        app.getSystemService(ActivityManager::class.java).clearApplicationUserData()
    }

    private fun asset(name: String): String = app.assets.open(name).bufferedReader().use { it.readText() }

    private fun loadIdentity(): Identity {
        vault.load(VAULT_IDENTITY)?.let { bytes -> Identity.import(bytes)?.let { return it } }
        return Identity.generate(crypto).also { vault.save(VAULT_IDENTITY, it.export()) }
    }

    private fun loadStaffSecret(): StaffSecretKeys? {
        vault.load(VAULT_SHIFT)?.let { text ->
            val card = QrCodes.decodeShift(String(text)) ?: return null
            val (credential, secret) = staffCrypto.credentialFrom(card) ?: return null
            keyChain.credential = credential
            card.reportKey?.let(keyChain::addReportKey)
            return secret
        }
        val seeds = vault.load(VAULT_STAFF) ?: return null
        if (seeds.size != 2 * Crypto.SEED) return null
        return staffCrypto.secretFromSeeds(seeds.copyOfRange(0, Crypto.SEED), seeds.copyOfRange(Crypto.SEED, seeds.size))
    }

    companion object {
        const val TAG = "Mesh"
        private const val VAULT_IDENTITY = "identity"
        private const val VAULT_STAFF = "staff"
        private const val VAULT_SHIFT = "shift"
    }
}
