package ro.safetyplease.core.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.SYSTEM
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.withSuffix
import ro.safetyplease.core.mkdirs
import ro.safetyplease.core.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One bad item, a full disk or a heavy pack: the session goes on, and says what happened. */
class SessionRobustnessTest {
    private val tmp = TempDir()
    private val sessions = mutableListOf<PingUpSession>()

    @AfterTest
    fun cleanUp() {
        sessions.forEach { it.close() }
        tmp.delete()
    }

    private fun session(platform: FakePlatform) = PingUpSession(platform).also { sessions += it }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun whatARunOfTheSessionThrowsGoesToTheLogAndTheRestGoesOn() = runBlocking {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        s.meshScope.launch { error("a packet made to break the phone") }
        eventually { platform.logs().any { "a packet made to break the phone" in it } }
        val after = CompletableDeferred<Unit>()
        s.meshScope.launch { after.complete(Unit) }
        withTimeout(5_000) { after.await() }
        // a run that is cancelled ends quietly: cancellation is no failure
        s.meshScope.launch { delay(60_000) }.cancel()
        delay(100)
        assertEquals(1, platform.logs().count { it.startsWith("uncaught") })
    }

    @Test
    fun aFullDiskIsToldWhileTheAppGoesOnWithWhatItKeepsInMemory() = runBlocking {
        val platform = FakePlatform(tmp)
        val s = session(platform)
        val file = platform.filesDir / "data" / "settings.json"
        // the temporary file can't be made, as on a full disk
        val blocker = file.withSuffix(".tmp")
        (blocker / "x").mkdirs()
        s.settings.update { it.copy(nickname = "Ana") }
        eventually { s.storage.value.writeFailed }
        // what the iPhone app does going to the background, and a switch of events: neither throws now
        s.flush()
        assertEquals("Ana", s.settings.value.nickname)
        eventually { platform.logs().any { it.startsWith("storage:") } }

        FileSystem.SYSTEM.deleteRecursively(blocker)
        eventually { !s.storage.value.writeFailed }
        assertTrue("\"Ana\"" in file.readText())
    }
}
