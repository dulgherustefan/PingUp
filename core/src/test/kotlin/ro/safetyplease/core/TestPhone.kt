package ro.safetyplease.core

import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.ListSerializer
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.crypto.Identity
import ro.safetyplease.core.crypto.StaffCrypto
import ro.safetyplease.core.crypto.StaffPublicKeys
import ro.safetyplease.core.crypto.StaffSecretKeys
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.ChatData
import ro.safetyplease.core.data.ChatMessage
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.mesh.MeshConfig
import ro.safetyplease.core.mesh.MeshEngine
import ro.safetyplease.core.mesh.PacketLog
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.util.nodePrefix
import java.io.File
import kotlin.random.Random

/** Chei de staff generate pentru un test; toate telefoanele au partea publica, doar staff-ul pe cea secreta. */
class TestEvent {
    private val boxSeed = testCrypto.random(32)
    private val signSeed = testCrypto.random(32)
    val staffCrypto = StaffCrypto(
        testCrypto,
        StaffPublicKeys(
            testCrypto.boxKeyPairFromSeed(boxSeed).publicKey,
            testCrypto.signKeyPairFromSeed(signSeed).publicKey,
        ),
    )
    val secret: StaffSecretKeys = staffCrypto.secretFromSeeds(boxSeed, signSeed)!!
}

/** Un telefon intreg fara Android: motor de mesh, chat si incidente peste radioul simulat. */
class TestPhone(
    val name: String,
    scope: TestScope,
    net: SimNet,
    clock: TestClock,
    dir: File,
    event: TestEvent,
    seed: Long,
) {
    private val bg = scope.backgroundScope
    val identity = Identity.generate(testCrypto)
    val nodeId: Long get() = identity.nodeId
    val radio = net.radio(name, nodeId.nodePrefix())
    val engine = MeshEngine(
        bg, radio, clock, Random(seed), nodeId, MeshConfig(allowTestPackets = true), PacketLog(false),
    ) { event.staffCrypto.verifyAck(it) }

    val friends = JsonStore(File(dir, "$name-friends.json"), ListSerializer(Friend.serializer()), emptyList(), bg)
    val groups = JsonStore(File(dir, "$name-groups.json"), ListSerializer(Group.serializer()), emptyList(), bg)
    val chatStore = JsonStore(File(dir, "$name-chat.json"), ChatData.serializer(), ChatData(), bg)
    val incidentStore = JsonStore(File(dir, "$name-incidents.json"), IncidentData.serializer(), IncidentData(), bg)

    var staffSecret: StaffSecretKeys? = null
    var team = "Echipa $name"
    val incoming = mutableListOf<ChatMessage>()
    val alerts = mutableListOf<StaffIncident>()
    val clearedAlerts = mutableListOf<String>()
    val reportUpdates = mutableListOf<MyReport>()

    val chat = ChatManager(
        bg, engine, testCrypto, identity, friends, groups, chatStore, clock, Random(seed + 1), { name },
    ) { message, _ -> incoming += message }

    val incidents = IncidentManager(
        bg, engine, testCrypto, event.staffCrypto, incidentStore, clock,
        staffSecret = { staffSecret }, teamName = { team },
        onStaffAlert = { alerts += it }, onStaffAlertCleared = { clearedAlerts += it },
        onReportUpdate = { reportUpdates += it },
    )

    init {
        chat.start()
        incidents.start()
        engine.start()
    }

    val messages: List<ChatMessage> get() = chatStore.value.messages
    val myReports: List<MyReport> get() = incidentStore.value.mine
    val staffIncidents: List<StaffIncident> get() = incidentStore.value.staff

    fun befriend(other: TestPhone) {
        chat.addFriend(other.chat.myCard())
        other.chat.addFriend(chat.myCard())
    }

    fun ignore(vararg others: TestPhone) = engine.setIgnoredPrefixes(others.map { it.nodeId.nodePrefix() }.toSet())
}
