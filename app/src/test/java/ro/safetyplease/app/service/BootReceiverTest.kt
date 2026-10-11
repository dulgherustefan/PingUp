package ro.safetyplease.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun rebootAndUpdateBringTheMeshBack() {
        assertTrue(revives("android.intent.action.BOOT_COMPLETED"))
        assertTrue(revives("android.intent.action.MY_PACKAGE_REPLACED"))
    }

    @Test
    fun nothingElseStartsItFromTheBackground() {
        // locked boot comes before the user unlocks, when the app's data can't be read yet
        assertFalse(revives("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(revives("android.intent.action.PACKAGE_REPLACED"))
        assertFalse(revives(null))
    }

    @Test
    fun meshComesBackOnlyIfItWasRunning() {
        assertEquals(BootAction.START, afterBoot(BOOT, wanted = true) { true })
        assertEquals(BootAction.START, afterBoot(UPDATE, wanted = true) { true })
        assertEquals(BootAction.NOTHING, afterBoot(BOOT, wanted = false) { error("permissions aren't checked") })
    }

    @Test
    fun withoutPermissionsTheUserIsAskedToOpenTheApp() {
        assertEquals(BootAction.NOTIFY, afterBoot(BOOT, wanted = true) { false })
    }

    @Test
    fun otherBroadcastsChangeNothing() {
        assertEquals(BootAction.NOTHING, afterBoot("android.intent.action.LOCKED_BOOT_COMPLETED", wanted = true) { false })
    }

    private companion object {
        const val BOOT = "android.intent.action.BOOT_COMPLETED"
        const val UPDATE = "android.intent.action.MY_PACKAGE_REPLACED"
    }
}
