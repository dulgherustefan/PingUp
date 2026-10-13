package ro.safetyplease.core.crypto

import ro.safetyplease.core.util.decomposed

/**
 * Nicknames that pass for staff or the organiser. A phone without a valid shift certificate may not take one, and its
 * neighbours don't show one it sends in HELLO.
 */
object ReservedNames {
    private val WORDS = listOf(
        "staff", "medic", "ambulanta", "smurd", "securitate", "security", "paza", "politia", "politie", "police",
        "jandarm", "pompier", "control", "organizator", "organiser", "organizer", "admin", "pingup",
    )
    private val LOOKALIKES = mapOf('0' to 'o', '1' to 'i', '3' to 'e', '4' to 'a', '5' to 's', '7' to 't', '@' to 'a', '$' to 's')
    private val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

    fun imitatesStaff(nickname: String): Boolean {
        val plain = nickname.lowercase().decomposed().filterNot { it.category in MARKS }
            .map { LOOKALIKES[it] ?: it }.filter { it.isLetter() }.joinToString("")
        return WORDS.any { it in plain }
    }
}
