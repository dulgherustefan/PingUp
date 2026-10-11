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

    @Test
    fun bundledSheetsAreFourNumberedDraftsAndOnlyCprSendsForADefibrillator() {
        val dir = File("src/main/assets/firstaid")
        val sheets = readSheets(dir.list().orEmpty().toList()) { File(dir, it).readText() }
        assertEquals(listOf("01-nu-respira", "02-inconstient-respira", "03-caldura", "04-sangerare"), sheets.map { it.id })
        for (sheet in sheets) {
            assertTrue(sheet.id, sheet.title.isNotEmpty() && sheet.summary.isNotEmpty())
            val steps = sheet.blocks.filterIsInstance<SheetBlock.Step>()
            assertEquals(sheet.id, (1..steps.size).toList(), steps.map { it.number })
            assertTrue(sheet.id, steps.size in 4..8)
            assertTrue("not approved by a medical lead yet: ${sheet.id}", sheet.draft)
            assertFalse("cedilla instead of comma below: ${sheet.id}", sheet.blocks.any { it.text.contains(Regex("[şţŞŢ]")) })
        }
        assertEquals(listOf("01-nu-respira"), sheets.filter { it.mentions("DEA") }.map { it.id })
    }
}
