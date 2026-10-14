package ro.safetyplease.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.exists
import ro.safetyplease.core.length
import ro.safetyplease.core.mkdirs
import ro.safetyplease.core.readText
import ro.safetyplease.core.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A full disk, a damaged file and the console's megabytes: what [JsonStore] does about each. */
@OptIn(ExperimentalCoroutinesApi::class)
class StoreFailureTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private fun friend(id: Long) = Friend(id, "F$id", "00", "00", id)

    private fun friends(file: Path, scope: CoroutineScope) = JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), scope)

    /** The data a store's file holds, without its version. */
    private fun dataOf(stored: String): String = Json.parseToJsonElement(stored).jsonObject.getValue("data").toString()

    @Test
    fun aWriteThatFailsKeepsTheChangeAndTriesAgainUntilItGoes() = runTest {
        val file = tmp.root / "settings.json"
        // the temporary file can't be made, as on a full disk; a folder with something in it doesn't go away either
        val blocker = file.withSuffix(".tmp")
        (blocker / "x").mkdirs()
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        store.update { it.copy(nickname = "Ana") }
        advanceTimeBy(1_000)
        assertTrue(store.health.value.writeFailed)
        assertFalse(file.exists())
        // a screen's flush doesn't throw either
        store.flush()
        assertEquals("Ana", store.value.nickname, "the change stays in memory")
        store.update { it.copy(teamName = "Medical 1") }
        advanceTimeBy(10_000)
        assertTrue(store.health.value.writeFailed, "still full")

        FileSystem.SYSTEM.deleteRecursively(blocker)
        advanceTimeBy(StoreWriter.MAX_RETRY_MS)
        assertFalse(store.health.value.writeFailed)
        val saved = JsonStore(file, Settings.serializer(), Settings(), backgroundScope).value
        assertEquals("Ana" to "Medical 1", saved.nickname to saved.teamName)
    }

    @Test
    fun aFlushWithNothingNewWritesNothing() = runTest {
        // the iPhone app flushes every store each time the phone locks
        val file = tmp.root / "events.json"
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        store.flush()
        assertFalse(file.exists(), "nothing changed, nothing written")
        store.update { it.copy(nickname = "Ana") }
        store.flush()
        assertTrue(file.exists())
        FileSystem.SYSTEM.delete(file)
        store.flush()
        advanceTimeBy(1_000)
        assertFalse(file.exists(), "that change is on disk already: neither the flush nor the debounced write repeat it")
        store.update { it.copy(teamName = "Medical 1") }
        store.flush()
        assertEquals("Medical 1", JsonStore(file, Settings.serializer(), Settings(), backgroundScope).value.teamName)
    }

    @Test
    fun aDamagedFileComesBackFromItsBackupWhichTheNextWriteKeeps() = runTest {
        val file = tmp.root / "friends.json"
        val store = friends(file, backgroundScope)
        store.update { it + friend(1) }
        store.flush()
        store.update { it + friend(2) }
        store.flush()
        file.writeText("{ nu e json")

        val again = friends(file, backgroundScope)
        assertEquals(listOf(1L), again.value.map { it.nodeId }, "the backup holds the write before")
        assertTrue(again.restored && again.health.value.restored)
        assertFalse(again.unreadable)
        // the damaged file goes at the next flush, even with nothing new
        again.flush()
        assertEquals(listOf(1L), friends(file, backgroundScope).value.map { it.nodeId })
        again.update { it + friend(3) }
        again.flush()
        assertEquals(listOf(1L, 3L), friends(file, backgroundScope).value.map { it.nodeId })
        val backup = Json.decodeFromString(ListSerializer(Friend.serializer()), dataOf(file.withSuffix(".bak").readText()))
        assertEquals(listOf(1L), backup.map { it.nodeId }, "the damaged file didn't replace the good backup")
    }

    @Test
    fun aFileFromANewerVersionStaysAsItIsWhateverItsBackupHolds() = runTest {
        val file = tmp.root / "settings.json"
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        store.update { it.copy(nickname = "Ana") }
        store.flush()
        store.update { it.copy(nickname = "Bogdan") }
        store.flush()
        val newer = """{"schemaVersion":2,"data":{"nickname":"Ana","pronouns":"ea"}}"""
        file.writeText(newer)
        val again = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        assertTrue(again.unreadable && again.health.value.unreadable)
        assertFalse(again.restored)
        again.update { it.copy(nickname = "Carmen") }
        again.flush()
        assertEquals(newer, file.readText())
    }

    @Test
    fun aBackupOfAnOlderVersionIsNotMigratedOverWhatCameAfter() = runTest {
        val file = tmp.root / "positions.json"
        file.writeText("{ nu e json")
        file.withSuffix(".bak").writeText("""{"schemaVersion":1,"data":{"nickname":"Ana"}}""")
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope, schemaVersion = 2)
        assertTrue(store.unreadable)
        assertEquals("", store.value.nickname)
    }

    @Test
    fun aLargeStoreStopsKeepingABackup() = runTest {
        val file = tmp.root / "incidents.json"
        val backup = file.withSuffix(".bak")
        val store = JsonStore(file, IncidentData.serializer(), IncidentData(), backgroundScope, encodeDefaults = false)
        store.update { IncidentData(staff = listOf(incident(0))) }
        store.flush()
        store.update { it.copy(dismissed = listOf("a")) }
        store.flush()
        assertTrue(backup.exists(), "a small file keeps its backup")
        store.update { IncidentData(staff = (0 until 2_000).map(::incident)) }
        store.flush()
        assertTrue(file.length() > JsonStore.BACKUP_MAX_BYTES)
        store.update { it.copy(dismissed = listOf("b")) }
        store.flush()
        assertFalse(backup.exists(), "past the limit no copy is made, and the stale one goes")
        assertEquals(listOf("b"), JsonStore(file, IncidentData.serializer(), IncidentData(), backgroundScope).value.dismissed)
    }

    /** One of a busy console's incidents, as the audit measured them. */
    private fun incident(i: Int) = StaffIncident(
        incidentId = i.toString(16).padStart(32, '0'), category = 1, severity = 3, zone = "main-stage", lat = 43.95, lon = 28.63,
        description = "x".repeat(140), nickname = null, reportedAt = 1_783_000_000_000L + i, receivedAt = 1_783_000_000_000L + i,
        hops = 3, status = 3, teamName = "Medical 2", statusAt = 1L, cancelHash = "ab".repeat(16), replyKey = "cd".repeat(32),
    )

    @Test
    fun withoutDefaultsTheIncidentsTakeLessAndAFileOfTheLastVersionReadsTheSame() = runTest {
        val data = IncidentData(staff = (0 until 5_000).map(::incident), mine = listOf(MyReport("f".repeat(32), 1, 3, "bar", createdAt = 5L)))
        // as 7839871 wrote incidents.json: every field, defaults included
        val old = Json { encodeDefaults = true }.encodeToString(IncidentData.serializer(), data)
        val file = tmp.root / "incidents.json"
        file.writeText("""{"schemaVersion":1,"data":$old}""")
        val store = JsonStore(file, IncidentData.serializer(), IncidentData(), backgroundScope, encodeDefaults = false)
        assertEquals(data, store.value)
        val before = file.length()
        store.update { it.copy(dismissed = listOf("x")) }
        store.flush()
        assertTrue(file.length() * 10 < before * 7, "${file.length()} bytes against $before")
        assertEquals(data.copy(dismissed = listOf("x")), JsonStore(file, IncidentData.serializer(), IncidentData(), backgroundScope).value)
    }

    @Test
    fun resetGoesThroughEvenWhereTheFileCantBeDeleted() = runTest {
        val file = tmp.root / "s.json"
        (file / "x").mkdirs()
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        store.reset()
        assertEquals(Settings(), store.value)
        FileSystem.SYSTEM.deleteRecursively(file)
        store.update { it.copy(nickname = "y") }
        store.flush()
        assertEquals("y", JsonStore(file, Settings.serializer(), Settings(), backgroundScope).value.nickname)
    }
}
