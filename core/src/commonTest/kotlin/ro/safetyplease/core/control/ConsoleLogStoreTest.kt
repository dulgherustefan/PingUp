package ro.safetyplease.core.control

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.SYSTEM
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.data.StoreWriter
import ro.safetyplease.core.data.withSuffix
import ro.safetyplease.core.exists
import ro.safetyplease.core.length
import ro.safetyplease.core.mkdirs
import ro.safetyplease.core.readText
import ro.safetyplease.core.writeText
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The console log as JSON lines: appended to, read from an older version's file, written whole only when it must. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleLogStoreTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val file get() = tmp.root / "console_log.json"

    private fun ack(i: Int) = LogEntry(
        1_783_000_000_000L + i, LogEvent.ACK, (i % 2000).toString(16).padStart(32, '0'), status = 2, team = "Medical 2",
        signedAt = 1_783_000_000_000L, signature = "ef".repeat(64), hops = 3,
    )

    private fun lines() = file.readText().split('\n').filter { it.isNotEmpty() }

    @Test
    fun entriesAreAppendedAsLinesAndWhatIsOnDiskStays() = runTest {
        val store = ConsoleLogStore(file, backgroundScope)
        store.append(listOf(ack(1), ack(2)))
        advanceTimeBy(1_000)
        val first = file.readText()
        assertEquals(3, lines().size, "a header, then a line per entry")
        store.append(listOf(ack(3)))
        store.flush()
        assertTrue(file.readText().startsWith(first), "an append leaves what is written alone")
        assertEquals(listOf(ack(1), ack(2), ack(3)), ConsoleLogStore(file, backgroundScope).value.entries)
        assertFalse(file.withSuffix(".bak").exists())
    }

    @Test
    fun aPhoneThatIsNoConsoleNeverMakesTheFile() = runTest {
        val store = ConsoleLogStore(file, backgroundScope)
        store.flush()
        advanceTimeBy(1_000)
        assertFalse(file.exists())
        store.append(listOf(ack(1)))
        store.flush()
        assertEquals(2, lines().size)
    }

    @Test
    fun theSingleObjectTheLastVersionWroteIsReadAndThenWrittenAsLines() = runTest {
        // console_log.json as 7839871 wrote it, through JsonStore: one object, every field, and its backup
        val old = Json { encodeDefaults = true }.encodeToString(ConsoleLog.serializer(), ConsoleLog(listOf(ack(1), ack(2))))
        file.writeText("""{"schemaVersion":1,"data":$old}""")
        file.withSuffix(".bak").writeText("""{"schemaVersion":1,"data":{"entries":[]}}""")
        val store = ConsoleLogStore(file, backgroundScope)
        assertEquals(listOf(ack(1), ack(2)), store.value.entries)
        store.append(listOf(ack(3)))
        store.flush()
        assertEquals(4, lines().size)
        assertFalse(file.withSuffix(".bak").exists(), "the log keeps no backup any more")
        assertEquals(listOf(ack(1), ack(2), ack(3)), ConsoleLogStore(file, backgroundScope).value.entries)
        assertEquals(listOf(ack(1), ack(2), ack(3)), ConsoleLogStore.read(file).entries)
    }

    @Test
    fun aLineCutShortByAPowerCutIsLeftOutAndTheFileIsWrittenWholeAgain() = runTest {
        val store = ConsoleLogStore(file, backgroundScope)
        store.append(listOf(ack(1), ack(2)))
        store.flush()
        file.writeText(file.readText() + """{"at":1783000000003,"event":"AC""")
        val again = ConsoleLogStore(file, backgroundScope)
        assertEquals(listOf(ack(1), ack(2)), again.value.entries)
        again.append(listOf(ack(3)))
        again.flush()
        assertEquals(4, lines().size, "the broken line is gone")
        assertEquals(listOf(ack(1), ack(2), ack(3)), ConsoleLogStore(file, backgroundScope).value.entries)
    }

    @Test
    fun takingEntriesBackWritesTheFileWhole() = runTest {
        val store = ConsoleLogStore(file, backgroundScope)
        store.append(listOf(ack(1), ack(2), ack(3)))
        store.flush()
        store.update { log -> log.copy(entries = log.entries.filterNot { it == ack(2) }) }
        store.flush()
        assertEquals(listOf(ack(1), ack(3)), ConsoleLogStore(file, backgroundScope).value.entries)
    }

    @Test
    fun aLogFromANewerVersionIsNeverWrittenOver() = runTest {
        val newer = """{"format":"lines","schemaVersion":2}""" + "\n"
        file.writeText(newer)
        val store = ConsoleLogStore(file, backgroundScope)
        assertTrue(store.unreadable && store.health.value.unreadable)
        store.append(listOf(ack(1)))
        store.flush()
        assertEquals(newer, file.readText())
        assertEquals(listOf(ack(1)), store.value.entries, "the log goes on in memory")
    }

    @Test
    fun anAppendThatFailsIsTriedAgainAsAWholeFile() = runTest {
        val store = ConsoleLogStore(file, backgroundScope)
        store.append(listOf(ack(1)))
        store.flush()
        // the file can't take an append: in its place, a folder that doesn't go away
        FileSystem.SYSTEM.delete(file)
        (file / "x").mkdirs()
        store.append(listOf(ack(2)))
        advanceTimeBy(1_000)
        assertTrue(store.health.value.writeFailed)
        FileSystem.SYSTEM.deleteRecursively(file)
        advanceTimeBy(StoreWriter.MAX_RETRY_MS)
        assertFalse(store.health.value.writeFailed)
        assertEquals(listOf(ack(1), ack(2)), ConsoleLogStore(file, backgroundScope).value.entries)
    }

    @Test
    fun aBusyEveningAppendsALineWhereTheWholeLogWasWritten() = runTest {
        // 2,000 reports and 10,000 ACKs: 8 MB as the last version wrote them, again at each change
        val grid = SiteGrid.of(Bounds(43.94, 28.62, 43.96, 28.65))
        val reports = (0 until 2_000).map { i ->
            reportEntry(
                StaffIncident(
                    incidentId = i.toString(16).padStart(32, '0'), category = 1, severity = 3, zone = "main-stage", lat = 43.95, lon = 28.63,
                    description = "x".repeat(140), nickname = null, reportedAt = 1_783_000_000_000L + i, receivedAt = 1_783_000_000_000L + i,
                    hops = 3, status = 3, teamName = "Medical 2", statusAt = 1L,
                ),
                grid,
            )
        }
        val entries = reports + (0 until 10_000).map(::ack)
        val old = Json { encodeDefaults = true }.encodeToString(ConsoleLog.serializer(), ConsoleLog(entries)).length
        val store = ConsoleLogStore(file, backgroundScope)
        store.append(entries)
        store.flush()
        val size = file.length()
        assertTrue(size * 2 < old, "$size bytes, against $old")
        store.append(listOf(ack(10_000)))
        store.flush()
        assertTrue(file.length() - size < 400, "one more ACK is one more line: ${file.length() - size} bytes")
    }
}
