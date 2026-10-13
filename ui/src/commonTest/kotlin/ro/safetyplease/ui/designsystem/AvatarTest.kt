package ro.safetyplease.ui.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarTest {
    @Test
    fun spacesAroundTheNameDontChangeTheColor() {
        // "Stefan" and "Stefan " used to land on different colors while typing in onboarding
        assertEquals(avatarIndex("Stefan", 6), avatarIndex("Stefan ", 6))
        assertEquals(avatarIndex("Ana", 6), avatarIndex("  Ana", 6))
    }

    @Test
    fun aPersonHasTheSameColorOnEveryPhone() {
        // the JVM's String.hashCode, which Android uses; an iPhone must land on the same color for the same name
        assertEquals(listOf(7, 7, 10, 10), listOf("Stefan", "Mara", "Ștefan", "Ana 🎪").map { avatarIndex(it, 12) })
    }
}
