package ro.safetyplease.app

import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.builtins.ListSerializer
import ro.safetyplease.app.chat.ChatManager
import ro.safetyplease.app.core.nodePrefix
import ro.safetyplease.app.crypto.Identity
import ro.safetyplease.app.crypto.StaffCrypto
import ro.safetyplease.app.crypto.StaffPublicKeys
import ro.safetyplease.app.crypto.StaffSecretKeys
import ro.safetyplease.app.crypto.testCrypto
import ro.safetyplease.app.data.ChatData
import ro.safetyplease.app.data.ChatMessage
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.Group
import ro.safetyplease.app.data.IncidentData
import ro.safetyplease.app.data.JsonStore
import ro.safetyplease.app.data.MyReport
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.incidents.IncidentManager
import ro.safetyplease.app.mesh.MeshConfig
import ro.safetyplease.app.mesh.MeshEngine
import ro.safetyplease.app.mesh.PacketLog
import ro.safetyplease.app.mesh.SimNet
import ro.safetyplease.app.mesh.TestClock
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
