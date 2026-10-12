package ro.safetyplease.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLocaleTest {
    private fun pick(vararg tags: String): String = AppLocale.pick(tags.map(Locale::forLanguageTag)).toLanguageTag()

    @Test
    fun englishPhoneGetsEnglishInItsRegion() {
        assertEquals("en-US", pick("en-US"))
        assertEquals("en-GB", pick("en-GB", "ro-RO"))
    }

    @Test
    fun romanianPhoneGetsRomanian() {
        assertEquals("ro-RO", pick("ro-RO", "en-US"))
        assertEquals("ro-MD", pick("ro-MD"))
    }

    @Test
    fun otherLanguagesFallBackToTheFirstSupportedOneThenRomanian() {
        assertEquals("en-US", pick("de-DE", "en-US"))
        assertEquals("ro-RO", pick("de-DE", "fr-FR"))
        assertEquals("ro-RO", pick())
    }

    @Test
    fun theDefaultListPutsTheAppLanguageFirst() {
        fun ordered(vararg tags: String) = AppLocale.ordered(tags.map(Locale::forLanguageTag)).map(Locale::toLanguageTag)
        // Compose reads the first of the list: German there would make the shared texts say "8 de zone"
        assertEquals(listOf("en-US", "de-DE"), ordered("de-DE", "en-US"))
        assertEquals(listOf("ro-RO", "de-DE", "fr-FR"), ordered("de-DE", "fr-FR"))
        assertEquals(listOf("ro-RO", "en-US"), ordered("ro-RO", "en-US"))
    }
}
