package ro.safetyplease.app.ui.me

import ro.safetyplease.ui.me.loadMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import ro.safetyplease.core.event.Added
import ro.safetyplease.core.event.Loaded
import ro.safetyplease.core.event.PackStatus
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.event_expired
import ro.safetyplease.ui.resources.event_known
import ro.safetyplease.ui.resources.event_loaded
import ro.safetyplease.ui.resources.event_older
import ro.safetyplease.ui.resources.event_shared_key
import ro.safetyplease.ui.resources.event_tampered
import ro.safetyplease.ui.resources.event_wrong_chain

class EventScreenTest {
    @Test
    fun everyRefusalSaysWhy() {
        val refusals = PackStatus.entries.filter { it != PackStatus.VALID }.map { loadMessage(Loaded(it, null)) }
        assertEquals(refusals.size, refusals.toSet().size)
        assertEquals(Res.string.event_tampered, loadMessage(Loaded(PackStatus.TAMPERED, null)))
        assertEquals(Res.string.event_wrong_chain, loadMessage(Loaded(PackStatus.WRONG_CHAIN, null)))
        assertEquals(Res.string.event_expired, loadMessage(Loaded(PackStatus.EXPIRED, null)))
    }

    @Test
    fun aValidPackSaysWhatBecameOfIt() {
        assertEquals(Res.string.event_loaded, loadMessage(Loaded(PackStatus.VALID, Added.NEW)))
        assertEquals(Res.string.event_loaded, loadMessage(Loaded(PackStatus.VALID, Added.UPDATED)))
        assertEquals(Res.string.event_known, loadMessage(Loaded(PackStatus.VALID, Added.KNOWN)))
        assertEquals(Res.string.event_older, loadMessage(Loaded(PackStatus.VALID, Added.OLDER)))
        assertEquals(Res.string.event_shared_key, loadMessage(Loaded(PackStatus.VALID, Added.SHARED_KEY)))
    }
}
