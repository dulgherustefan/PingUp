package ro.safetyplease.app.service

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
}
