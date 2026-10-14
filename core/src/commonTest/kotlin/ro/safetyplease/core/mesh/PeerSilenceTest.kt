package ro.safetyplease.core.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeerSilenceTest {
    private val minute = 60_000L
    private val alone = MeshState()
    private fun heard(atMs: Long) = MeshState(seen = listOf(SeenPeer("a", 1, 0, -60, atMs)))
    private val linked = MeshState(links = listOf(LinkInfo(1, "a", 7L, null, 0, true, 0)))

    @Test
    fun aPhoneAloneSinceTheStartSaysHowLong() {
        val silence = PeerSilence(sinceMs = 0)
        assertNull(silence.silentMinutes(alone, 4 * minute), "a few minutes is a quiet corner")
        assertEquals(12, silence.silentMinutes(alone, 12 * minute + 30_000))
        assertFalse(silence.heardAny)
    }

    @Test
    fun theLastPhoneHeardCountsAndALinkUpSaysNothing() {
        val silence = PeerSilence(sinceMs = 0)
        silence.update(heard(10 * minute), 10 * minute)
        assertTrue(silence.heardAny)
        assertEquals(25, silence.silentMinutes(alone, 35 * minute))
        assertNull(silence.silentMinutes(linked, 40 * minute))
        assertEquals(6, silence.silentMinutes(alone, 46 * minute), "from the link that was up")
        silence.update(heard(5 * minute), 50 * minute)
        assertEquals(10, silence.silentMinutes(alone, 50 * minute), "an older sighting doesn't move it back")
    }
}
