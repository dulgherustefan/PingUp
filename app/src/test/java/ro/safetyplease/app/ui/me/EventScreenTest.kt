package ro.safetyplease.app.ui.me

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.safetyplease.app.R
import ro.safetyplease.core.event.Added
import ro.safetyplease.core.event.Loaded
import ro.safetyplease.core.event.PackStatus

class EventScreenTest {
    @Test
    fun everyRefusalSaysWhy() {
        val refusals = PackStatus.entries.filter { it != PackStatus.VALID }.map { loadMessage(Loaded(it, null)) }
        assertEquals(refusals.size, refusals.toSet().size)
        assertEquals(R.string.event_tampered, loadMessage(Loaded(PackStatus.TAMPERED, null)))
        assertEquals(R.string.event_wrong_chain, loadMessage(Loaded(PackStatus.WRONG_CHAIN, null)))
        assertEquals(R.string.event_expired, loadMessage(Loaded(PackStatus.EXPIRED, null)))
    }

    @Test
    fun aValidPackSaysWhatBecameOfIt() {
        assertEquals(R.string.event_loaded, loadMessage(Loaded(PackStatus.VALID, Added.NEW)))
        assertEquals(R.string.event_loaded, loadMessage(Loaded(PackStatus.VALID, Added.UPDATED)))
        assertEquals(R.string.event_known, loadMessage(Loaded(PackStatus.VALID, Added.KNOWN)))
        assertEquals(R.string.event_older, loadMessage(Loaded(PackStatus.VALID, Added.OLDER)))
        assertEquals(R.string.event_shared_key, loadMessage(Loaded(PackStatus.VALID, Added.SHARED_KEY)))
    }
}
