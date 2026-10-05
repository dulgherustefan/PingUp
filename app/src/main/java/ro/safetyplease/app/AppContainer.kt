package ro.safetyplease.app

import android.app.ActivityManager
import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ro.safetyplease.app.ble.BleRadio
import ro.safetyplease.app.chat.ChatManager
import ro.safetyplease.app.core.Clock
import ro.safetyplease.app.core.hexToBytes
import ro.safetyplease.app.crypto.Crypto
import ro.safetyplease.app.crypto.Identity
import ro.safetyplease.app.crypto.KeyVault
import ro.safetyplease.app.crypto.SodiumCrypto
import ro.safetyplease.app.crypto.StaffCard
import ro.safetyplease.app.crypto.StaffCrypto
import ro.safetyplease.app.crypto.StaffPublicKeys
import ro.safetyplease.app.crypto.StaffRole
import ro.safetyplease.app.crypto.StaffSecretKeys
import ro.safetyplease.app.data.ChatData
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.data.IncidentData
import ro.safetyplease.app.data.JsonStore
import ro.safetyplease.app.data.Role
import ro.safetyplease.app.data.Settings
import ro.safetyplease.app.incidents.IncidentManager
import ro.safetyplease.app.location.LocationSource
import ro.safetyplease.app.mesh.MeshConfig
import ro.safetyplease.app.mesh.MeshEngine
import ro.safetyplease.app.mesh.PacketLog
import ro.safetyplease.app.mesh.PowerPolicy
import ro.safetyplease.app.service.Notifier
import ro.safetyplease.app.venue.Venue
import java.io.File
import java.security.SecureRandom
import kotlin.random.asKotlinRandom

object AndroidClock : Clock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun monoMs(): Long = SystemClock.elapsedRealtime()
}

/** Tot ce traieste cat procesul: identitate, date, motorul de mesh si managerii de chat si incidente. */
class AppContainer(private val app: Application) {
    val clock: Clock = AndroidClock
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Un singur fir pentru mesh, radio, chat si incidente: nicio stare de retea nu are nevoie de lacate. */
    val meshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    val crypto: Crypto = SodiumCrypto(LazySodiumAndroid(SodiumAndroid()))
    private val vault = KeyVault(app)
    val identity: Identity = loadIdentity()

    private val dataDir = File(app.filesDir, "data")
    val settings = JsonStore(File(dataDir, "settings.json"), Settings.serializer(), Settings(), ioScope)
    val friends = JsonStore(File(dataDir, "friends.json"), ListSerializer(Friend.serializer()), emptyList(), ioScope)
    val groups = JsonStore(File(dataDir, "groups.json"), ListSerializer(Group.serializer()), emptyList(), ioScope)
    val chatStore = JsonStore(File(dataDir, "chat.json"), ChatData.serializer(), ChatData(), ioScope)
    val incidentStore = JsonStore(File(dataDir, "incidents.json"), IncidentData.serializer(), IncidentData(), ioScope)

    val venue: Venue = Venue.parse(asset("venue.json"))
    val staffCrypto = StaffCrypto(crypto, loadStaffPublic())

    @Volatile
    var staffSecret: StaffSecretKeys? = loadStaffSecret()
        private set

    val log = PacketLog(BuildConfig.DEBUG).apply { sink = { Log.d(TAG, it) } }
    val radio = BleRadio(app, meshScope, identity.nodeId) { Log.i(TAG, it) }
    val engine = MeshEngine(
        meshScope, radio, clock, SecureRandom().asKotlinRandom(), identity.nodeId,
        MeshConfig(nicknameInHello = BuildConfig.DEBUG, allowTestPackets = BuildConfig.DEBUG),
        log,
    ) { staffCrypto.verifyAck(it) }

    val notifier = Notifier(app, venue)
    val location = LocationSource(app)

    /** Pana cand (timp monoton) scanarea si advertising-ul raman in LOW_LATENCY dupa un incident trimis. */
    val boostUntil = MutableStateFlow(0L)

    @Volatile
    var inForeground = false

    @Volatile
    var openConversation: String? = null

    val chat = ChatManager(
        meshScope, engine, crypto, identity, friends, groups, chatStore, clock, SecureRandom().asKotlinRandom(),
        myNickname = { settings.value.nickname },
    ) { message, sender ->
        if (!inForeground || openConversation != message.conversation) notifier.chatMessage(message, sender)
    }

    val incidents = IncidentManager(
        meshScope, engine, crypto, staffCrypto, incidentStore, clock,
        staffSecret = { staffSecret },
        teamName = { settings.value.teamName },
        onStaffAlert = { notifier.staffAlert(it) },
        onStaffAlertCleared = { notifier.cancelStaffAlert(it) },
        onReportUpdate = { if (!inForeground) notifier.reportUpdate(it) },
        onReportSent = { boostUntil.value = clock.monoMs() + PowerPolicy.BOOST_MS },
    )

    init {
        meshScope.launch {
            engine.start()
            chat.start()
            incidents.start()
            settings.state.collect { s ->
                engine.setRole(staff = s.role == Role.STAFF, anchor = s.role == Role.ANCHOR)
                engine.setNickname(s.nickname)
                engine.setIgnoredPrefixes(s.ignoredPrefixes.toSet())
            }
        }
    }

    /** False daca QR-ul nu apartine acestui eveniment. O ancora nu pastreaza cheile secrete: sta nesupravegheata. */
    fun activateStaff(card: StaffCard): Boolean {
        val secret = staffCrypto.secretFromSeeds(card.boxSeed, card.signSeed) ?: return false
        if (card.role == StaffRole.ANCHOR) {
            vault.delete(VAULT_STAFF)
            staffSecret = null
            settings.update { it.copy(role = Role.ANCHOR, teamName = card.teamName, anchorZone = card.zone) }
        } else {
            vault.save(VAULT_STAFF, card.boxSeed + card.signSeed)
            staffSecret = secret
            settings.update { it.copy(role = Role.STAFF, teamName = card.teamName, anchorZone = "") }
            incidents.reprocessCached()
        }
        return true
    }

    fun leaveStaff() {
        vault.delete(VAULT_STAFF)
        staffSecret = null
        settings.update { it.copy(role = Role.PARTICIPANT, teamName = "", anchorZone = "") }
    }

    fun wipeAllData() {
        app.getSystemService(ActivityManager::class.java).clearApplicationUserData()
    }

    private fun asset(name: String): String = app.assets.open(name).bufferedReader().use { it.readText() }

    private fun loadIdentity(): Identity {
        vault.load(VAULT_IDENTITY)?.let { bytes -> Identity.import(bytes)?.let { return it } }
        return Identity.generate(crypto).also { vault.save(VAULT_IDENTITY, it.export()) }
    }

    private fun loadStaffPublic(): StaffPublicKeys {
        val json = Json.parseToJsonElement(asset("staff_public.json")).jsonObject
        return StaffPublicKeys(
            json.getValue("box").jsonPrimitive.content.hexToBytes(),
            json.getValue("sign").jsonPrimitive.content.hexToBytes(),
        )
    }

    private fun loadStaffSecret(): StaffSecretKeys? {
        val seeds = vault.load(VAULT_STAFF) ?: return null
        if (seeds.size != 2 * Crypto.SEED) return null
        return staffCrypto.secretFromSeeds(seeds.copyOfRange(0, Crypto.SEED), seeds.copyOfRange(Crypto.SEED, seeds.size))
    }

    companion object {
        const val TAG = "Mesh"
        private const val VAULT_IDENTITY = "identity"
        private const val VAULT_STAFF = "staff"
    }
}
