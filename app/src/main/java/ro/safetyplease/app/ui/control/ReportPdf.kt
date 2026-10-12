package ro.safetyplease.app.ui.control

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/**
 * The report on A4 pages, drawn with the platform's PdfDocument: headings, then label and value on one line, the value
 * right-aligned. A sentence alone wraps over the full width. [footer] gets the page number.
 */
internal fun writeReportPdf(doc: ReportDoc, footer: (Int) -> String, file: File) {
    PdfWriter(footer).use { pdf ->
        pdf.text(doc.title, pdf.title, gapAfter = 4f)
        pdf.text(doc.subtitle, pdf.small, gapAfter = 12f)
        for (section in doc.sections) {
            pdf.ensure(pdf.heading.fontSpacing * 2 + pdf.body.fontSpacing)
            pdf.text(section.title, pdf.heading, gapBefore = 10f, gapAfter = 2f)
            pdf.rule()
            for (line in section.lines) {
                if (line.value.isEmpty()) pdf.text(line.label, pdf.body) else pdf.row(line.label, line.value)
            }
        }
        pdf.save(file)
    }
}

private class PdfWriter(private val footer: (Int) -> String) : AutoCloseable {
    private val document = PdfDocument()
    private var page: PdfDocument.Page? = null
    private var number = 0
    private var y = 0f

    val title = paint(18f, bold = true)
    val heading = paint(12.5f, bold = true)
    val body = paint(10f)
    val small = paint(8.5f).apply { color = GRAY }
    private val line = Paint().apply { color = LIGHT_GRAY; strokeWidth = 0.6f }

    private val canvas: Canvas
        get() = (page ?: newPage()).canvas

    fun text(text: String, paint: Paint, gapBefore: Float = 0f, gapAfter: Float = 3f) {
        y += gapBefore
        for (part in wrap(text, paint, WIDTH)) {
            ensure(paint.fontSpacing)
            y += paint.fontSpacing
            canvas.drawText(part, MARGIN, y - paint.descent(), paint)
        }
        y += gapAfter
    }

    fun row(label: String, value: String) {
        val valueWidth = body.measureText(value)
        if (valueWidth > WIDTH / 2) return text("$label: $value", body)
        val labels = wrap(label, body, WIDTH - valueWidth - GUTTER)
        ensure(body.fontSpacing * labels.size)
        labels.forEachIndexed { i, part ->
            y += body.fontSpacing
            canvas.drawText(part, MARGIN, y - body.descent(), body)
            if (i == 0) canvas.drawText(value, MARGIN + WIDTH - body.measureText(value), y - body.descent(), body)
        }
        y += 3f
    }

    fun rule() {
        canvas.drawLine(MARGIN, y, MARGIN + WIDTH, y, line)
        y += 2f
    }

    /** Starts a new page unless [height] still fits on this one. */
    fun ensure(height: Float) {
        if (page == null || y + height > PAGE_H - MARGIN - FOOTER) newPage()
    }

    fun save(file: File) {
        finishPage()
        file.outputStream().use(document::writeTo)
    }

    override fun close() = document.close()

    private fun newPage(): PdfDocument.Page {
        finishPage()
        number++
        val next = document.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create())
        page = next
        y = MARGIN
        return next
    }

    private fun finishPage() {
        val current = page ?: return
        current.canvas.drawText(footer(number), MARGIN, PAGE_H - MARGIN / 2, small)
        document.finishPage(current)
        page = null
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            var n = paint.breakText(rest, true, width, null).coerceAtLeast(1)
            if (n < rest.length) {
                val space = rest.lastIndexOf(' ', n)
                if (space > 0) n = space
            }
            out += rest.substring(0, n).trimEnd()
            rest = rest.substring(n).trimStart()
        }
        return out.ifEmpty { listOf("") }
    }

    private companion object {
        /** A4 in PostScript points. */
        const val PAGE_W = 595
        const val PAGE_H = 842
        const val MARGIN = 48f
        const val WIDTH = PAGE_W - 2 * MARGIN
        const val GUTTER = 16f
        const val FOOTER = 16f
        val GRAY = Color.rgb(0x6E, 0x6E, 0x73)
        val LIGHT_GRAY = Color.rgb(0xD1, 0xD1, 0xD6)

        fun paint(size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.BLACK
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}
