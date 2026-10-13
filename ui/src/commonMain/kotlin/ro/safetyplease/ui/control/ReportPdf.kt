package ro.safetyplease.ui.control

import okio.Path

/** The four kinds of text on the report's pages, in the phone's own font. */
enum class PdfFont(val size: Float, val bold: Boolean = false, val gray: Boolean = false) {
    TITLE(18f, bold = true),
    HEADING(12.5f, bold = true),
    BODY(10f),
    SMALL(8.5f, gray = true),
}

/** What the report is drawn on: pages of [PdfCanvas.PAGE_W] by [PdfCanvas.PAGE_H] points, y going down. */
interface PdfCanvas {
    /** The distance from one line of [font] to the next. */
    fun lineHeight(font: PdfFont): Float

    /** How far [font] reaches below its baseline. */
    fun descent(font: PdfFont): Float

    fun width(text: String, font: PdfFont): Float

    fun text(text: String, x: Float, baseline: Float, font: PdfFont)

    /** A thin light gray line across, at [y]. */
    fun rule(fromX: Float, toX: Float, y: Float)

    fun startPage()

    fun finishPage()

    companion object {
        /** A4 in PostScript points. */
        const val PAGE_W = 595
        const val PAGE_H = 842
    }
}

/** A new PDF file at [path], with the pages [draw] puts on the phone's own canvas. */
expect fun writePdf(path: Path, draw: (PdfCanvas) -> Unit)

/**
 * The report on A4 pages: headings, then label and value on one line, the value right-aligned. A sentence alone wraps
 * over the full width. [footer] gets the page number.
 */
fun writeReportPdf(doc: ReportDoc, footer: (Int) -> String, path: Path) = writePdf(path) { ReportLayout(it, footer).draw(doc) }

/** Where [writeReportPdf] puts each line, on any canvas. */
class ReportLayout(private val canvas: PdfCanvas, private val footer: (Int) -> String) {
    private var page = 0
    private var open = false
    private var y = 0f

    fun draw(doc: ReportDoc) {
        text(doc.title, PdfFont.TITLE, gapAfter = 4f)
        text(doc.subtitle, PdfFont.SMALL, gapAfter = 12f)
        for (section in doc.sections) {
            // a heading never ends a page on its own
            ensure(canvas.lineHeight(PdfFont.HEADING) * 2 + canvas.lineHeight(PdfFont.BODY))
            text(section.title, PdfFont.HEADING, gapBefore = 10f, gapAfter = 2f)
            rule()
            for (line in section.lines) {
                if (line.value.isEmpty()) text(line.label, PdfFont.BODY) else row(line.label, line.value)
            }
        }
        finishPage()
    }

    private fun text(text: String, font: PdfFont, gapBefore: Float = 0f, gapAfter: Float = 3f) {
        y += gapBefore
        for (part in wrap(text, font, WIDTH)) {
            ensure(canvas.lineHeight(font))
            y += canvas.lineHeight(font)
            canvas.text(part, MARGIN, y - canvas.descent(font), font)
        }
        y += gapAfter
    }

    private fun row(label: String, value: String) {
        val font = PdfFont.BODY
        val valueWidth = canvas.width(value, font)
        if (valueWidth > WIDTH / 2) return text("$label: $value", font)
        val labels = wrap(label, font, WIDTH - valueWidth - GUTTER)
        ensure(canvas.lineHeight(font) * labels.size)
        labels.forEachIndexed { i, part ->
            y += canvas.lineHeight(font)
            canvas.text(part, MARGIN, y - canvas.descent(font), font)
            if (i == 0) canvas.text(value, MARGIN + WIDTH - valueWidth, y - canvas.descent(font), font)
        }
        y += 3f
    }

    private fun rule() {
        canvas.rule(MARGIN, MARGIN + WIDTH, y)
        y += 2f
    }

    /** Starts a new page unless [height] still fits on this one. */
    private fun ensure(height: Float) {
        if (!open || y + height > PdfCanvas.PAGE_H - MARGIN - FOOTER) newPage()
    }

    private fun newPage() {
        finishPage()
        page++
        canvas.startPage()
        open = true
        y = MARGIN
    }

    private fun finishPage() {
        if (!open) return
        canvas.text(footer(page), MARGIN, PdfCanvas.PAGE_H - MARGIN / 2, PdfFont.SMALL)
        canvas.finishPage()
        open = false
    }

    /** [text] in lines no wider than [width], broken at the last space that fits, or in a word longer than a line. */
    private fun wrap(text: String, font: PdfFont, width: Float): List<String> {
        val out = ArrayList<String>()
        var rest = text
        while (rest.isNotEmpty()) {
            var n = fitting(rest, font, width).coerceAtLeast(1)
            if (n < rest.length) {
                val space = rest.lastIndexOf(' ', n)
                if (space > 0) n = space
            }
            out += rest.substring(0, n).trimEnd()
            rest = rest.substring(n).trimStart()
        }
        return out.ifEmpty { listOf("") }
    }

    /** How many of [text]'s first characters fit in [width]; a longer start is never narrower. */
    private fun fitting(text: String, font: PdfFont, width: Float): Int {
        var fits = 0
        var over = text.length + 1
        while (over - fits > 1) {
            val mid = (fits + over) / 2
            if (canvas.width(text.substring(0, mid), font) <= width) fits = mid else over = mid
        }
        return fits
    }

    internal companion object {
        const val MARGIN = 48f
        const val WIDTH = PdfCanvas.PAGE_W - 2 * MARGIN
        const val GUTTER = 16f
        const val FOOTER = 16f
    }
}
