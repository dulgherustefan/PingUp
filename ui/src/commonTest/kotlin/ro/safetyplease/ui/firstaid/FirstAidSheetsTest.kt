package ro.safetyplease.ui.firstaid

import ro.safetyplease.ui.names
import ro.safetyplease.ui.readText
import ro.safetyplease.ui.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals("Sângele curge mult dintr-o rană.", sheet.summary, "a line right under a block continues it")
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
        assertTrue(parseSheet("a", "$sample\n\nAprobat:").draft, "an empty approval approves nothing")
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
        val dir = repoFile("app/src/main/assets/${sheetDirectory(language)}")
        return readSheets(dir.names().toList()) { (dir / it).readText() }
    }

    @Test
    fun bundledSheetsAreFourNumberedDraftsAndOnlyCprSendsForADefibrillator() {
        for ((language, word) in mapOf("ro" to "DEA", "en" to "AED")) {
            val sheets = bundled(language)
            assertEquals(listOf("01-nu-respira", "02-inconstient-respira", "03-caldura", "04-sangerare"), sheets.map { it.id }, language)
            for (sheet in sheets) {
                assertTrue(sheet.title.isNotEmpty() && sheet.summary.isNotEmpty(), sheet.id)
                val steps = sheet.blocks.filterIsInstance<SheetBlock.Step>()
                assertEquals((1..steps.size).toList(), steps.map { it.number }, sheet.id)
                assertTrue(steps.size in 4..8, sheet.id)
                assertTrue(sheet.draft, "not approved by a medical lead yet: ${sheet.id}")
                assertFalse(sheet.blocks.any { it.text.contains(Regex("[şţŞŢ]")) }, "cedilla instead of comma below: ${sheet.id}")
            }
            assertEquals(listOf("01-nu-respira"), sheets.filter { it.mentions(word) }.map { it.id }, language)
        }
    }

    @Test
    fun englishCopiesFollowTheRomanianOnesStepForStep() {
        for ((ro, en) in bundled("ro").zip(bundled("en"))) {
            assertEquals(ro.id, en.id)
            assertEquals(ro.blocks.map { it::class }, en.blocks.map { it::class }, ro.id)
            assertEquals(ro.approval == null, en.approval == null, ro.id)
        }
        val titles = bundled("en").map { it.title }
        for (sheet in bundled("en")) {
            for (quoted in Regex("\"([^\"]+)\"").findAll(sheet.blocks.joinToString(" ") { it.text }).map { it.groupValues[1] }) {
                assertTrue(quoted in titles || quoted == "AED", "${sheet.id} points to a sheet that does not exist: $quoted")
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
