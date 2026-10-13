package ro.safetyplease.ui

import androidx.compose.ui.text.intl.Locale
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getPluralString
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.map_a11y_summary
import kotlin.test.Test
import kotlin.test.assertEquals

class AppLanguageTest {
    @Test
    fun theSharedTextsSpeakThePinnedLanguageWithItsPluralRules() = runBlocking {
        pinLanguage("ro")
        assertEquals("ro", Locale.current.language)
        // Romanian puts "de" only from 20 on; read with another language's rules, 8 would take it too
        assertEquals("Harta evenimentului, 8 zone", getPluralString(Res.plurals.map_a11y_summary, 8, 8))
    }
}
