package ro.safetyplease.core

import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.ListSerializer
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.crypto.Revocation
import ro.safetyplease.core.crypto.Revocations
import ro.safetyplease.core.crypto.ShiftCard
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.BlockedPerson
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.ChatStore
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.mesh.CachedIncidents
import ro.safetyplease.core.mesh.MeshConfig
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.PacketLog
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.IncidentAck
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.util.nodePrefix
import java.io.File
import kotlin.random.Random

/**
 * Staff keys generated for one test; every phone has the public part, only staff has the secret one. Next to the
 * build's shared keys, a chain: root, organiser, and an event that holds from an hour before the test clock's start
 * for two days. With [dayKey], reporters seal to the event's report key instead of the shared one.
 */
class TestEvent(private val dayKey: Boolean = false) {
    private val boxSeed = testCrypto.random(32)
    private val signSeed = testCrypto.random(32)
    val publicKeys = StaffPublicKeys(
        testCrypto.boxKeyPairFromSeed(boxSeed).publicKey,
        testCrypto.signKeyPairFromSeed(signSeed).publicKey,
    )
    val staffCrypto = StaffCrypto(testCrypto, publicKeys)
    val secret: StaffSecretKeys = staffCrypto.secretFromSeeds(boxSeed, signSeed)!!

    val root = testCrypto.signKeyPair()
    val organiser = testCrypto.signKeyPair()
    val eventKey = testCrypto.signKeyPair()
    private val organiserCert = Certificate.issue(testCrypto, CertRole.ORGANISER, organiser.publicKey, 0, 0xFFFFFFFFL, "Org", root)
    val eventCert = Certificate.issue(testCrypto, CertRole.EVENT, eventKey.publicKey, START - 3600, START + 2 * DAY, "Fest", organiser)
    private val reportSeed = testCrypto.random(32)
    val reportCert = Certificate.issue(
        testCrypto, CertRole.REPORT_KEY, testCrypto.boxKeyPairFromSeed(reportSeed).publicKey, START - 3600, START + DAY, "", eventKey,
    )

    fun chain(clock: Clock) = KeyChain(
        testCrypto, clock, publicKeys.sign, root.publicKey, listOf(eventCert, organiserCert), if (dayKey) listOf(reportCert) else emptyList(),
    )

    /** A shift from [from] to [until] seconds after the test clock's start; [opensReports] gives it the day's report key. */
    fun shift(role: Int, team: String, from: Long = -60, until: Long = DAY, opensReports: Boolean = true): ShiftCard {
        val seed = testCrypto.random(32)
        val key = testCrypto.signKeyPairFromSeed(seed).publicKey
        val cert = Certificate.issue(testCrypto, role, key, START + from, START + until, team, eventKey)
        val report = opensReports && role != CertRole.ANCHOR
        return ShiftCard(cert, seed, reportCert.takeIf { report }, reportSeed.takeIf { report }, null, "")
    }

    fun revocation(vararg certs: Certificate): ByteArray = Revocation.sign(testCrypto, eventKey, START, certs.map { it.id }).encode()

    companion object {
        /** The test clock's wall time at 0, in seconds. */
        const val START = 1_760_000_000L
        const val DAY = 86_400L
    }
}

/** A whole phone without Android: mesh engine, chat and incidents over the simulated radio. */
class TestPhone(
    val name: String,
    scope: TestScope,
    net: SimNet,
    clock: TestClock,
    dir: File,
    event: TestEvent,
    seed: Long,
    val identity: Identity = Identity.generate(testCrypto),
    canAdvertise: Boolean = true,
    nicknameInHello: Boolean = false,
) {
    private val bg = scope.backgroundScope
    val nodeId: Long get() = identity.nodeId
    val radio = net.radio(name, nodeId.nodePrefix(), canAdvertise = canAdvertise)
    val meshCache = JsonStore(File(dir, "$name-mesh.json"), CachedIncidents.serializer(), CachedIncidents(), bg)
    val keyChain = event.chain(clock)
    val staffCrypto = StaffCrypto(testCrypto, event.publicKeys, keyChain)
    val engine = MeshEngine(
        bg, radio, clock, Random(seed), nodeId, MeshConfig(allowTestPackets = true, nicknameInHello = nicknameInHello), PacketLog(false), meshCache,
        keyChain,
    )

    val friends = JsonStore(File(dir, "$name-friends.json"), ListSerializer(Friend.serializer()), emptyList(), bg)
    val groups = JsonStore(File(dir, "$name-groups.json"), ListSerializer(Group.serializer()), emptyList(), bg)
    val blocked = JsonStore(File(dir, "$name-blocked.json"), ListSerializer(BlockedPerson.serializer()), emptyList(), bg)
    val chatStore = ChatStore(File(dir, "$name-chat"), bg)
    val incidentStore = JsonStore(File(dir, "$name-incidents.json"), IncidentData.serializer(), IncidentData(), bg)

    var staffSecret: StaffSecretKeys? = null
    var team = "Echipa $name"
    val incoming = mutableListOf<ChatMessage>()
    val alerts = mutableListOf<StaffIncident>()
    val clearedAlerts = mutableListOf<String>()
    val reportUpdates = mutableListOf<MyReport>()

    /** Our reports as they were each time a new response from the console arrived. */
    val reportResponses = mutableListOf<MyReport>()

    /** ACKs IncidentManager reported: the ACK, whether this phone signed it, and its hops. */
    val acks = mutableListOf<Triple<IncidentAck, Boolean, Int>>()
    var staffCap = IncidentManager.MAX_STAFF
    var sharesZone = true

    val chat = ChatManager(
        bg, engine, testCrypto, identity, friends, groups, blocked, chatStore, clock, Random(seed + 1), { name },
        shareZone = { sharesZone },
    ) { message, _ -> incoming += message }

    val incidents = IncidentManager(
        bg, engine, testCrypto, staffCrypto, incidentStore, clock,
        staffSecret = { staffSecret }, teamName = { team },
        onStaffAlert = { alerts += it }, onStaffAlertCleared = { clearedAlerts += it },
        onReportUpdate = { reportUpdates += it },
        onReportResponse = { reportResponses += it },
        onAck = { ack, own, hops -> acks += Triple(ack, own, hops) },
        staffCap = { staffCap },
    )

    val revocations = Revocations(
        bg, engine, keyChain, Revocations.store(File(dir, "$name-revocations.json"), bg),
        resends = { false },
    )

    init {
        chat.start()
        incidents.start()
        revocations.start()
        engine.start()
    }

    val messages: List<ChatMessage> get() = chatStore.value.messages
    val myReports: List<MyReport> get() = incidentStore.value.mine
    val staffIncidents: List<StaffIncident> get() = incidentStore.value.staff

    /** As the app does with an SPS2 code: the shift's keys, its team and its role in HELLO. */
    fun activate(card: ShiftCard) {
        val (credential, secret) = staffCrypto.credentialFrom(card)!!
        keyChain.credential = credential
        staffSecret = secret
        team = card.teamName
        engine.setRole(staff = card.role != StaffRole.ANCHOR, anchor = card.role == StaffRole.ANCHOR)
    }

    fun befriend(other: TestPhone) {
        chat.addFriend(other.chat.myCard())
        other.chat.addFriend(chat.myCard())
    }

    /** By the prefix the others advertise now, so no link opens, and by their nodeId's, so one that opens is closed. */
    fun ignore(vararg others: TestPhone) =
        engine.setIgnoredPrefixes(others.flatMap { listOf(it.radio.prefix, it.nodeId.nodePrefix()) }.toSet())
}
