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
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneSet
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.announce.target
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
import ro.safetyplease.core.venue.GeoFix
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

    private val control = testCrypto.signKeyPair()
    private val controlCredential = Credential(
        Certificate.issue(testCrypto, CertRole.CONTROL, control.publicKey, 0, 0xFFFFFFFFL, "Control", testCrypto.signKeyPair()), control,
    )

    private fun evacuation(s: PingUpSession, now: Long, zone: String, seq: Long = 1) = Notice.sign(
        testCrypto, controlCredential, NoticeTopic.ALERT, NoticeLevel.EVACUATION, seq, now, now + 3600, 8,
        extras = NoticeExtras(zones = ZoneSet.target(s.venue, setOf(zone))),
    )

    /** A point inside each of the first two zones of the bundled venue. */
    private fun inside(s: PingUpSession, index: Int): GeoFix {
        val zone = s.venue.zones[index]
        assertEquals(zone.id, s.venue.zoneAt(zone.center.lat, zone.center.lon)?.id)
        return GeoFix(zone.center.lat, zone.center.lon, 5f, 0)
    }

    @Test
    fun anEvacuationForItsZoneReachesAParticipantWithTheNoticesOff() {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        s.settings.update { it.copy(role = Role.PARTICIPANT, notices = false) }
        val now = platform.clock.wallMs() / 1000
        platform.location.value = inside(s, 0).copy(timeMs = platform.clock.wallMs())
        val (a, b) = s.venue.zones.take(2).map { it.id }

        s.onNotice(evacuation(s, now, b, seq = 1), own = false)
        assertTrue(platform.shown.posted.none { it.startsWith("notice") }, "another zone's evacuation stays behind the opt-in")
        s.onNotice(evacuation(s, now, a, seq = 2), own = false)
        assertEquals(listOf("notice Anunț ${NoticeTopic.ALERT} LOUD"), platform.shown.posted.filter { it.startsWith("notice") })
        val pause = Notice.sign(testCrypto, controlCredential, NoticeTopic.ALERT, NoticeLevel.PAUSE, 3, now, now + 3600, 3, params = listOf("16:00"))
        s.onNotice(pause, own = false)
        assertEquals(1, platform.shown.posted.count { it.startsWith("notice") }, "a pause stays behind the opt-in")
        s.onNotice(Notice.sign(testCrypto, controlCredential, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 4, now, now + 3600, 0, flags = Notice.CANCEL), own = false)
        assertEquals("notice Anunț ${NoticeTopic.ALERT} NONE", platform.shown.posted.last(), "the withdrawal takes it away")
    }

    @Test
    fun aPhoneThatWalksIntoAnEvacuatedZoneSoundsThen() = runBlocking {
        val platform = FakePlatform(tmp)
        val now = platform.clock.wallMs() / 1000
        val probeDir = TempDir()
        val probe = PingUpSession(FakePlatform(probeDir))
        val venue = probe.venue
        val (a, b) = venue.zones.take(2).map { it.id }
        val inA = inside(probe, 0)
        val inB = inside(probe, 1)
        probe.close()
        probeDir.delete()
        val evac = Notice.sign(
            testCrypto, controlCredential, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 1, now, now + 3600, 8,
            extras = NoticeExtras(zones = ZoneSet.target(venue, setOf(b))),
        )
        val board = NoticeBoard(listOf(HeldNotice(evac.topic, evac.seq, evac.encode().toHex(), 0)))
        (platform.filesDir / "data").mkdirs()
        (platform.filesDir / "data" / "notices.json").writeText(Json.encodeToString(NoticeBoard.serializer(), board))

        val s = session(platform)
        s.settings.update { it.copy(role = Role.PARTICIPANT, notices = false) }
        platform.location.value = inA.copy(timeMs = platform.clock.wallMs())
        delay(200)
        assertTrue(platform.shown.posted.none { it.startsWith("notice") }, "zone $a isn't evacuated")
        platform.location.value = inB.copy(timeMs = platform.clock.wallMs())
        eventually { platform.shown.posted.any { it.startsWith("notice") } }
        assertEquals(listOf("notice Anunț ${NoticeTopic.ALERT} LOUD"), platform.shown.posted.filter { it.startsWith("notice") })
        platform.location.value = inA.copy(timeMs = platform.clock.wallMs())
        delay(100)
        platform.location.value = inB.copy(timeMs = platform.clock.wallMs() + 1)
        delay(200)
        assertEquals(1, platform.shown.posted.count { it.startsWith("notice") }, "once")
    }
}
