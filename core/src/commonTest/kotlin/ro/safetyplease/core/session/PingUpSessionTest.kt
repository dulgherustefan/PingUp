package ro.safetyplease.core.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.announce.HeldNotice
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeBoard
import ro.safetyplease.core.announce.NoticeExtras
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.StaffCard
import ro.safetyplease.core.crypto.StaffRole
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.event.EventPacks
import ro.safetyplease.core.mesh.BatteryReading
import ro.safetyplease.core.mesh.PowerProfile
import ro.safetyplease.core.mkdirs
import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PingUpSessionTest {
    private val tmp = TempDir()
    private val sessions = mutableListOf<PingUpSession>()

    @AfterTest
    fun cleanUp() {
        sessions.forEach { it.close() }
        tmp.delete()
    }

    private fun session(platform: FakePlatform = FakePlatform(tmp)) = PingUpSession(platform).also { sessions += it }

    /** A staff QR for the demo keys the debug build ships with. */
    private fun demoCard(role: StaffRole): StaffCard {
        val json = repoFile("app/src/debug/assets/demo_staff.json").readText()
        fun seed(name: String) = Regex("\"$name\"\\s*:\\s*\"([0-9a-f]+)\"").find(json)!!.groupValues[1].hexToBytes()
        return StaffCard(seed("boxSeed"), seed("signSeed"), role, "Medical 1", "scena")
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun theIdentityIsMadeOnceAndKeptInTheVault() {
        val vault = MemoryVault()
        val first = session(FakePlatform(tmp, vault))
        assertEquals(setOf("identity"), vault.entries.keys)
        val again = session(FakePlatform(tmp, vault))
        assertEquals(first.identity.nodeId, again.identity.nodeId)
        assertFalse(again.settings.value.identityReplaced)
    }

    @Test
    fun anIdentityTheVaultCanNoLongerOpenIsSetAsideAndReplaced() {
        val vault = MemoryVault()
        val lost = session(FakePlatform(tmp, vault)).identity
        vault.sealed += "identity"
        val fresh = session(FakePlatform(tmp, vault))
        assertNotEquals(lost.nodeId, fresh.identity.nodeId)
        assertEquals(listOf("identity"), vault.setAside)
        assertTrue(fresh.settings.value.identityReplaced, "the app tells the person their old identity is gone")
    }

    @Test
    fun aStaffCardMakesThePhoneStaffAndLeavingUndoesIt() {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        assertTrue(s.activateStaff(demoCard(StaffRole.STAFF)))
        assertEquals(Role.STAFF, s.settings.value.role)
        assertEquals("Medical 1", s.settings.value.teamName)
        assertNotNull(s.staffSecret)
        assertTrue(platform.vault.exists("staff"))

        s.leaveStaff()
        assertEquals(Role.PARTICIPANT, s.settings.value.role)
        assertNull(s.staffSecret)
        assertFalse(platform.vault.exists("staff"))
    }

    @Test
    fun anAnchorKeepsNoSecretKeys() {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        assertTrue(s.activateStaff(demoCard(StaffRole.ANCHOR)))
        assertEquals(Role.ANCHOR, s.settings.value.role)
        assertEquals("scena", s.settings.value.anchorZone)
        assertNull(s.staffSecret)
        assertFalse(platform.vault.exists("staff"))
    }

    @Test
    fun aCardForOtherKeysIsRefused() {
        // the release build's keys: the demo seeds don't open them
        val s = session(FakePlatform(tmp, assets = "app/src/main/assets"))
        assertFalse(s.activateStaff(demoCard(StaffRole.STAFF)))
        assertEquals(Role.PARTICIPANT, s.settings.value.role)
    }

    @Test
    fun theStaffKeysComeBackWithTheNextSession() {
        val platform = FakePlatform(tmp)
        session(platform).apply {
            activateStaff(demoCard(StaffRole.STAFF))
            settings.flush()
            close()
        }
        val again = session(FakePlatform(tmp, platform.vault))
        assertNotNull(again.staffSecret)
        assertEquals(Role.STAFF, again.settings.value.role)
    }

    @Test
    fun switchingEventsSavesEverythingThenStartsAgain() {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        s.settings.update { it.copy(nickname = "Ana") }
        s.switchEvent("")
        assertEquals(1, platform.restarts)
        // on disk at once, not 150 ms later: the process may end right after
        assertTrue("\"Ana\"" in (platform.filesDir / "data" / "settings.json").readText())
    }

    @Test
    fun flushingWritesWhatWaitsAtOnceAndStaysInTheSession() {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        s.settings.update { it.copy(nickname = "Ana") }
        // the iPhone app flushes as it goes to the background: iOS may end a suspended app without a word
        s.flush()
        assertTrue("\"Ana\"" in (platform.filesDir / "data" / "settings.json").readText())
        assertEquals(0, platform.restarts)
    }

    @Test
    fun anEventPackComesOnlyOverHttpsAndUpToItsLimit() = runBlocking {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        platform.downloads["https://ex.ro/pack"] = "pack".encodeToByteArray()
        platform.downloads["http://ex.ro/pack"] = "pack".encodeToByteArray()
        platform.downloads["https://ex.ro/big"] = ByteArray(EventPacks.MAX_BYTES + 10)
        assertEquals("pack", s.downloadEventPack("  https://ex.ro/pack "))
        assertNull(s.downloadEventPack("http://ex.ro/pack"))
        assertNull(s.downloadEventPack("https://ex.ro/big"), "a file that big isn't a pack")
    }

    @Test
    fun theBatteryProfileFollowsTheRole() {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        assertEquals(80, s.battery.state.value.percent)
        assertEquals(PowerProfile.LEAF, s.battery.state.value.profile)
        s.activateStaff(demoCard(StaffRole.STAFF))
        platform.batteryListener!!(BatteryReading(79, charging = false, temperatureC = 30))
        assertEquals(PowerProfile.STAFF, s.battery.state.value.profile)
    }

    @Test
    fun theMeshRunsAtThePowerTheBatteryAllowsAndStopsWithItsCaller() = runBlocking {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        val radio = platform.radios.single()
        val run = launch(Dispatchers.Default) { s.runMesh(MutableStateFlow(false)) }
        eventually { radio.plans().isNotEmpty() }
        assertEquals(PowerProfile.LEAF, radio.plans().last().profile)

        platform.batteryListener!!(BatteryReading(10, charging = false, temperatureC = 30))
        eventually { radio.plans().last().profile == PowerProfile.ECONOMY }

        run.cancelAndJoin()
        eventually { radio.calls().last() == "stop" }
        assertEquals("start", radio.calls().first())
    }

    @Test
    fun theMapStateComesFromTheNoticesOnTheBoard() {
        val platform = FakePlatform(tmp)
        val now = platform.clock.wallMs() / 1000
        val control = testCrypto.signKeyPair()
        val credential = Credential(
            Certificate.issue(testCrypto, CertRole.CONTROL, control.publicKey, 0, 0xFFFFFFFFL, "Control", testCrypto.signKeyPair()), control,
        )
        val states = List(8) { if (it == 1) ZoneState.CLOSED else ZoneState.OPEN }
        val notice = Notice.sign(testCrypto, credential, NoticeTopic.MAP_STATE, 1, 1, now, now + 3600, 0, extras = NoticeExtras(states = states))
        val board = NoticeBoard(listOf(HeldNotice(notice.topic, notice.seq, notice.encode().toHex(), 0)))
        (platform.filesDir / "data").mkdirs()
        (platform.filesDir / "data" / "notices.json").writeText(Json.encodeToString(NoticeBoard.serializer(), board))

        val s = session(platform)
        assertEquals(8, s.venue.zones.size)
        assertEquals(mapOf(s.venue.zones[1].id to ZoneState.CLOSED), s.mapState.value.zones)
        assertNull(s.mapState.value.evacuation)
        assertEquals(StraightLine, s.walkDistance)
    }
}
