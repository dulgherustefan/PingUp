package ro.safetyplease.ui.control

import ro.safetyplease.ui.control.ReportLayout.Companion.FOOTER
import ro.safetyplease.ui.control.ReportLayout.Companion.MARGIN
import ro.safetyplease.ui.control.ReportLayout.Companion.WIDTH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportLayoutTest {
    private class Drawn(val text: String, val x: Float, val baseline: Float, val font: PdfFont)

    /** Five points a letter and twelve a line, so the layout can be counted by hand. */
    private class CountingCanvas : PdfCanvas {
        val pages = mutableListOf<MutableList<Drawn>>()

        override fun lineHeight(font: PdfFont) = 12f

        override fun descent(font: PdfFont) = 3f

        override fun width(text: String, font: PdfFont) = 5f * text.length

        override fun text(text: String, x: Float, baseline: Float, font: PdfFont) {
            pages.last() += Drawn(text, x, baseline, font)
        }

        override fun rule(fromX: Float, toX: Float, y: Float) = Unit

        override fun startPage() {
            pages += mutableListOf<Drawn>()
        }

        override fun finishPage() = Unit
    }

    private fun pages(vararg sections: DocSection): List<List<Drawn>> =
        CountingCanvas().also { ReportLayout(it) { page -> "pagina $page" }.draw(ReportDoc("Raport", "Plaja", sections.toList())) }.pages

    @Test
    fun aFigureSitsAtTheRightEdgeOfItsLabelsLine() {
        val page = pages(DocSection("Incidente", listOf(DocLine("Total", "12")))).single()
        val value = page.single { it.text == "12" }
        assertEquals(MARGIN + WIDTH - 10f, value.x)
        assertEquals(page.single { it.text == "Total" }.baseline, value.baseline)
    }

    @Test
    fun aSentenceWrapsAtSpacesWithoutLosingAWord() {
        val sentence = List(60) { "cuvânt$it" }.joinToString(" ")
        val lines = pages(DocSection("Limite", listOf(DocLine(sentence)))).single().filter { it.font == PdfFont.BODY }
        assertTrue(lines.size > 1)
        assertTrue(lines.all { 5f * it.text.length <= WIDTH })
        assertEquals(sentence, lines.joinToString(" ") { it.text })
    }

    @Test
    fun aWordLongerThanALineIsCutWhereItStopsFitting() {
        val word = "x".repeat(250)
        val lines = pages(DocSection("Limite", listOf(DocLine(word)))).single().filter { it.font == PdfFont.BODY }
        assertEquals(listOf(99, 99, 52), lines.map { it.text.length })
    }

    @Test
    fun aFigureTooWideForHalfTheLineFollowsItsLabel() {
        val squares = List(30) { "E$it" }.joinToString(", ")
        val lines = pages(DocSection("Rețea", listOf(DocLine("Fără date", squares)))).single().filter { it.font == PdfFont.BODY }
        assertEquals("Fără date: $squares", lines.joinToString(" ") { it.text })
        assertTrue(lines.all { it.x == MARGIN })
    }

    @Test
    fun longReportsGoOnNewPagesEachWithItsNumber() {
        val pages = pages(DocSection("Pe ore", List(200) { DocLine("ora $it", "$it") }))
        assertTrue(pages.size > 1)
        pages.forEachIndexed { i, page ->
            assertEquals("pagina ${i + 1}", page.last().text)
            assertTrue(page.dropLast(1).all { it.baseline <= PdfCanvas.PAGE_H - MARGIN - FOOTER })
        }
        assertEquals(200, pages.flatten().count { it.text.startsWith("ora ") })
    }

    @Test
    fun aHeadingNeverEndsAPage() {
        val pages = pages(*Array(80) { DocSection("Secțiunea $it", listOf(DocLine("rând", "$it"))) })
        assertTrue(pages.size > 1)
        for (page in pages) assertTrue(page.dropLast(1).last().font != PdfFont.HEADING)
    }
}
