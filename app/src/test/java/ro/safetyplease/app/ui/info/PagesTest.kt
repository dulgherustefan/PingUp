package ro.safetyplease.app.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.event.InfoPage
import ro.safetyplease.core.event.InfoPages
import ro.safetyplease.core.event.PageBlock
import java.io.File

class PagesTest {
    private fun files(language: String): Map<String, String> {
        val dir = File("src/main/assets/${pagesDirectory(language)}")
        return dir.list().orEmpty().associateWith { File(dir, it).readText() }
    }

    private fun bundled(language: String): List<InfoPage> = InfoPages.read(files(language))

    private val placeholder = mapOf("ro" to "(sursa: organizatorul)", "en" to "(source: organiser)")

    @Test
    fun theAppShipsFivePagesInBothLanguagesAsDraftsWithEveryFactLeftToTheOrganiser() {
        for (language in listOf("ro", "en")) {
            val pages = bundled(language)
            assertEquals(
                language,
                listOf("01-plaja", "02-transport", "03-bratara-si-bani", "04-reducerea-riscurilor", "05-spatii-sigure"),
                pages.map { it.id },
            )
            for (page in pages) {
                assertTrue(page.id, page.title.isNotEmpty() && page.summary.isNotEmpty())
                assertTrue("not approved by the organiser yet: ${page.id}", page.draft)
                assertTrue("a page with nothing to read: ${page.id}", page.blocks.count { it is PageBlock.Bullet } >= 3)
                val text = page.blocks.joinToString("\n") { it.text }
                assertTrue("no placeholder for the organiser's facts: ${page.id}", placeholder.getValue(language) in text)
            }
        }
    }

    @Test
    fun englishCopiesFollowTheRomanianOnesBlockForBlock() {
        for ((ro, en) in bundled("ro").zip(bundled("en"))) {
            assertEquals(ro.id, en.id)
            assertEquals(ro.id, ro.blocks.map { it::class }, en.blocks.map { it::class })
            for ((a, b) in ro.blocks.zip(en.blocks)) {
                assertEquals(ro.id, a.text.contains(placeholder.getValue("ro")), b.text.contains(placeholder.getValue("en")))
            }
            assertEquals(ro.id, ro.approval == null, en.approval == null)
        }
    }

    @Test
    fun textsUseCommaBelowAndEnglishHasNoRomanianLetters() {
        for ((name, text) in files("ro")) assertFalse("cedilla instead of comma below: $name", text.contains(Regex("[şţŞŢ]")))
        for ((name, text) in files("en")) assertFalse("Romanian left over: $name", text.contains(Regex("[ăâîșțĂÂÎȘȚ„]")))
    }

    @Test
    fun theShippedPagesFitInAPackWithRoom() {
        val bytes = listOf("ro", "en").sumOf { language -> files(language).values.sumOf { it.toByteArray().size } }
        assertTrue("$bytes bytes", bytes < InfoPages.MAX_TOTAL_BYTES / 4)
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
