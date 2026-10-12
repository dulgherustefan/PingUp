package ro.safetyplease.ui.control

import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.PDFKit.PDFDocument
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportPdfIosTest {
    private val path = (NSTemporaryDirectory() + "raport-${Random.nextLong().toULong()}.pdf").toPath()

    @AfterTest
    fun clean() = FileSystem.SYSTEM.delete(path)

    @Test
    fun theReportIsAPdfWhoseTextReadsBackWithItsRomanianLetters() {
        val lines = List(120) { DocLine("Intervenție la ora $it", "$it") }
        writeReportPdf(ReportDoc("Raport · Ștefănești", "Plaja · 12 octombrie", listOf(DocSection("Pe ore", lines))), { "pagina $it" }, path)

        val pdf = PDFDocument(uRL = NSURL.fileURLWithPath(path.toString()))
        assertTrue(pdf.pageCount.toInt() > 1)
        val text = pdf.string.orEmpty()
        assertTrue("Raport · Ștefănești" in text, text.take(200))
        assertTrue("Intervenție la ora 119" in text)
        assertTrue("pagina ${pdf.pageCount}" in text)
        assertEquals("%PDF", FileSystem.SYSTEM.read(path) { readUtf8(4) })
    }
}
