package ro.safetyplease.ui.text

import kotlinx.datetime.TimeZone
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared formatter against java.text, which the Android app used before: same text for every pattern the texts use. */
class DateTextJavaTest {
    private val patterns = listOf("d MMMM, HH:mm", "d MMM, HH:mm", "d MMM", "EEEE, d MMMM", "MMMM d, HH:mm", "MMM d, HH:mm", "MMM d", "EEEE, MMMM d", "EEE", "HH:mm")

    @Test
    fun everyPatternMatchesJavaInBothLanguages() {
        val random = Random(12)
        val zone = java.util.TimeZone.getTimeZone("Europe/Bucharest")
        for ((locale, names) in listOf(Locale.forLanguageTag("ro-RO") to Romanian, Locale.US to English)) {
            repeat(500) {
                val ms = random.nextLong(1_700_000_000_000L, 1_900_000_000_000L)
                for (pattern in patterns) {
                    val java = SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(ms)
                    assertEquals(java, formatDate(pattern, ms, names, TimeZone.of("Europe/Bucharest")), "$pattern at $ms")
                }
            }
        }
    }
}
