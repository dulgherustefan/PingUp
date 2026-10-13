package ro.safetyplease.ui.info

import ro.safetyplease.core.event.InfoPage
import ro.safetyplease.core.event.InfoPages
import ro.safetyplease.core.event.PageBlock
import ro.safetyplease.ui.names
import ro.safetyplease.ui.readText
import ro.safetyplease.ui.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PagesTest {
    private fun files(language: String): Map<String, String> {
        val dir = repoFile("app/src/main/assets/${pagesDirectory(language)}")
        return dir.names().associateWith { (dir / it).readText() }
    }

    private fun bundled(language: String): List<InfoPage> = InfoPages.read(files(language))

    private val placeholder = mapOf("ro" to "(sursa: organizatorul)", "en" to "(source: organiser)")

    @Test
    fun theAppShipsFivePagesInBothLanguagesAsDraftsWithEveryFactLeftToTheOrganiser() {
        for (language in listOf("ro", "en")) {
            val pages = bundled(language)
            assertEquals(
                listOf("01-plaja", "02-transport", "03-bratara-si-bani", "04-reducerea-riscurilor", "05-spatii-sigure"),
                pages.map { it.id },
                language,
            )
            for (page in pages) {
                assertTrue(page.title.isNotEmpty() && page.summary.isNotEmpty(), page.id)
                assertTrue(page.draft, "not approved by the organiser yet: ${page.id}")
                assertTrue(page.blocks.count { it is PageBlock.Bullet } >= 3, "a page with nothing to read: ${page.id}")
                val text = page.blocks.joinToString("\n") { it.text }
                assertTrue(placeholder.getValue(language) in text, "no placeholder for the organiser's facts: ${page.id}")
            }
        }
    }

    @Test
    fun englishCopiesFollowTheRomanianOnesBlockForBlock() {
        for ((ro, en) in bundled("ro").zip(bundled("en"))) {
            assertEquals(ro.id, en.id)
            assertEquals(ro.blocks.map { it::class }, en.blocks.map { it::class }, ro.id)
            for ((a, b) in ro.blocks.zip(en.blocks)) {
                assertEquals(a.text.contains(placeholder.getValue("ro")), b.text.contains(placeholder.getValue("en")), ro.id)
            }
            assertEquals(ro.approval == null, en.approval == null, ro.id)
        }
    }

    @Test
    fun textsUseCommaBelowAndEnglishHasNoRomanianLetters() {
        for ((name, text) in files("ro")) assertFalse(text.contains(Regex("[şţŞŢ]")), "cedilla instead of comma below: $name")
        for ((name, text) in files("en")) assertFalse(text.contains(Regex("[ăâîșțĂÂÎȘȚ„]")), "Romanian left over: $name")
    }

    @Test
    fun theShippedPagesFitInAPackWithRoom() {
        val bytes = listOf("ro", "en").sumOf { language -> files(language).values.sumOf { it.encodeToByteArray().size } }
        assertTrue(bytes < InfoPages.MAX_TOTAL_BYTES / 4, "$bytes bytes")
    }

    @Test
    fun englishPhonesReadTheEnglishCopies() {
        assertEquals("pages-en", pagesDirectory("en"))
        assertEquals("pages", pagesDirectory("ro"))
        assertEquals("pages", pagesDirectory("de"))
    }

    @Test
    fun consecutiveBulletsShareACardAndTheSummaryIsLeftOut() {
        val page = InfoPages.parse("a", "# T\n\nSummary\n\n## H\n- one\n- two\nPlain\n\n! Careful\n- three")!!
        val parts = pageParts(page)
        assertEquals(listOf(1, 2, 1, 1), parts.map { it.size })
        assertTrue(parts[0].single() is PageBlock.Heading)
        assertEquals(listOf("one", "two Plain"), parts[1].map { it.text })
        assertTrue(parts[2].single() is PageBlock.Warning)
        assertEquals("three", parts[3].single().text)
    }
}
