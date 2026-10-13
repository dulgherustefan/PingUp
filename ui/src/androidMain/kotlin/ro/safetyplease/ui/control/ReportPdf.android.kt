package ro.safetyplease.ui.control

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import okio.Path

actual fun writePdf(path: Path, draw: (PdfCanvas) -> Unit) {
    val document = PdfDocument()
    try {
        draw(AndroidCanvas(document))
        path.toFile().outputStream().use(document::writeTo)
    } finally {
        document.close()
    }
}

/** Android's own PDF pages, drawn with its default font. */
private class AndroidCanvas(private val document: PdfDocument) : PdfCanvas {
    private val paints = PdfFont.entries.associateWith { font ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = font.size
            color = if (font.gray) GRAY else Color.BLACK
            typeface = if (font.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
    private val line = Paint().apply {
        color = LIGHT_GRAY
        strokeWidth = 0.6f
    }
    private var page: PdfDocument.Page? = null
    private var number = 0

    override fun lineHeight(font: PdfFont) = paints.getValue(font).fontSpacing

    override fun descent(font: PdfFont) = paints.getValue(font).descent()

    override fun width(text: String, font: PdfFont) = paints.getValue(font).measureText(text)

    override fun text(text: String, x: Float, baseline: Float, font: PdfFont) {
        page?.canvas?.drawText(text, x, baseline, paints.getValue(font))
    }

    override fun rule(fromX: Float, toX: Float, y: Float) {
        page?.canvas?.drawLine(fromX, y, toX, y, line)
    }

    override fun startPage() {
        number++
        page = document.startPage(PdfDocument.PageInfo.Builder(PdfCanvas.PAGE_W, PdfCanvas.PAGE_H, number).create())
    }

    override fun finishPage() {
        page?.let(document::finishPage)
        page = null
    }

    private companion object {
        val GRAY = Color.rgb(0x6E, 0x6E, 0x73)
        val LIGHT_GRAY = Color.rgb(0xD1, 0xD1, 0xD6)
    }
}
