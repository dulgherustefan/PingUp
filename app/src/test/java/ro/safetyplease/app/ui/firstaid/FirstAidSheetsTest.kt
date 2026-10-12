package ro.safetyplease.app.ui.firstaid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FirstAidSheetsTest {
    private val sample = """
        # Sângerează mult

        Sângele curge mult
        dintr-o rană.

        1. Sună la 112.
        2. Apasă tare pe rană
           cu un material curat.

        ! Nu scoate obiectele înfipte.
        3. Ține apăsat.
    """.trimIndent()

    @Test
    fun parsesTitleTextStepsAndWarnings() {
        val sheet = parseSheet("04-sangerare", sample)
        assertEquals("04-sangerare", sheet.id)
        assertEquals("Sângerează mult", sheet.title)
        assertEquals("a line right under a block continues it", "Sângele curge mult dintr-o rană.", sheet.summary)
        assertEquals(
            listOf(
                SheetBlock.Text("Sângele curge mult dintr-o rană."),
                SheetBlock.Step(1, "Sună la 112."),
                SheetBlock.Step(2, "Apasă tare pe rană cu un material curat."),
                SheetBlock.Warning("Nu scoate obiectele înfipte."),
                SheetBlock.Step(3, "Ține apăsat."),
            ),
            sheet.blocks,
        )
    }

    @Test
    fun withoutAnApprovalLineTheSheetIsADraft() {
        val draft = parseSheet("a", sample)
        assertNull(draft.approval)
        assertTrue(draft.draft)
        val approved = parseSheet("a", "$sample\n\nAprobat: Dr. Ana Pop, șef medical, 12.06.2027")
        assertEquals("Dr. Ana Pop, șef medical, 12.06.2027", approved.approval)
        assertFalse(approved.draft)
        assertTrue("an empty approval approves nothing", parseSheet("a", "$sample\n\nAprobat:").draft)
    }

    @Test
    fun partsSkipTheSummaryAndKeepConsecutiveStepsTogether() {
        val parts = sheetParts(parseSheet("a", sample))
        assertEquals(listOf(2, 1, 1), parts.map { it.size })
        assertEquals(listOf(1, 2), parts[0].map { (it as SheetBlock.Step).number })
        assertTrue(parts[1].single() is SheetBlock.Warning)
    }

    @Test
    fun loaderReadsOnlySheetsInFileNameOrder() {
        val files = mapOf("02-b.md" to "# B\n\nb", "01-a.md" to "# A\n\na", "notes.txt" to "# X")
        val sheets = readSheets(files.keys.toList()) { files.getValue(it) }
        assertEquals(listOf("01-a", "02-b"), sheets.map { it.id })
        assertEquals(listOf("A", "B"), sheets.map { it.title })
    }

    private fun bundled(language: String): List<FirstAidSheet> {
        val dir = File("src/main/assets/${sheetDirectory(language)}")
        return readSheets(dir.list().orEmpty().toList()) { File(dir, it).readText() }
    }

    @Test
    fun bundledSheetsAreFourNumberedDraftsAndOnlyCprSendsForADefibrillator() {
        for ((language, word) in mapOf("ro" to "DEA", "en" to "AED")) {
            val sheets = bundled(language)
            assertEquals(language, listOf("01-nu-respira", "02-inconstient-respira", "03-caldura", "04-sangerare"), sheets.map { it.id })
            for (sheet in sheets) {
                assertTrue(sheet.id, sheet.title.isNotEmpty() && sheet.summary.isNotEmpty())
                val steps = sheet.blocks.filterIsInstance<SheetBlock.Step>()
                assertEquals(sheet.id, (1..steps.size).toList(), steps.map { it.number })
                assertTrue(sheet.id, steps.size in 4..8)
                assertTrue("not approved by a medical lead yet: ${sheet.id}", sheet.draft)
                assertFalse("cedilla instead of comma below: ${sheet.id}", sheet.blocks.any { it.text.contains(Regex("[şţŞŢ]")) })
            }
            assertEquals(language, listOf("01-nu-respira"), sheets.filter { it.mentions(word) }.map { it.id })
        }
    }

    @Test
    fun englishCopiesFollowTheRomanianOnesStepForStep() {
        for ((ro, en) in bundled("ro").zip(bundled("en"))) {
            assertEquals(ro.id, en.id)
            assertEquals(ro.id, ro.blocks.map { it::class }, en.blocks.map { it::class })
            assertEquals(ro.id, ro.approval == null, en.approval == null)
        }
        val titles = bundled("en").map { it.title }
        for (sheet in bundled("en")) {
            for (quoted in Regex("\"([^\"]+)\"").findAll(sheet.blocks.joinToString(" ") { it.text }).map { it.groupValues[1] }) {
                assertTrue("${sheet.id} points to a sheet that does not exist: $quoted", quoted in titles || quoted == "AED")
            }
        }
    }

    @Test
    fun englishApprovalLineApprovesToo() {
        assertEquals("Dr. Ana Pop, 12.06.2027", parseSheet("a", "$sample\n\nApproved: Dr. Ana Pop, 12.06.2027").approval)
    }

    @Test
    fun englishPhonesReadTheEnglishCopies() {
        assertEquals("firstaid-en", sheetDirectory("en"))
        assertEquals("firstaid", sheetDirectory("ro"))
        assertEquals("firstaid", sheetDirectory("de"))
    }
}
