package ro.safetyplease.app.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarTest {
    @Test
    fun spacesAroundTheNameDontChangeTheColor() {
        // "Stefan" and "Stefan " used to land on different colors while typing in onboarding
        assertEquals(avatarIndex("Stefan", 6), avatarIndex("Stefan ", 6))
        assertEquals(avatarIndex("Ana", 6), avatarIndex("  Ana", 6))
    }
}
