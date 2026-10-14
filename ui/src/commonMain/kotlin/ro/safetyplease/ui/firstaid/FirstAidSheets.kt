package ro.safetyplease.ui.firstaid

import ro.safetyplease.core.session.PingUpSession

/** What a sheet says, in order: plain text, a numbered step, or a warning. */
sealed interface SheetBlock {
    val text: String

    data class Text(override val text: String) : SheetBlock
    data class Step(val number: Int, override val text: String) : SheetBlock
    data class Warning(override val text: String) : SheetBlock
}

/**
 * A first-aid sheet for whoever is next to the patient. [approval] names who approved the text and when; until the
 * event's medical lead has done so it is null and the sheet shows as a draft.
 */
data class FirstAidSheet(val id: String, val title: String, val blocks: List<SheetBlock>, val approval: String?) {
    /** The first paragraph: when the sheet applies. */
    val summary: String get() = blocks.firstOrNull { it is SheetBlock.Text }?.text.orEmpty()

    val draft: Boolean get() = approval == null

    fun mentions(word: String): Boolean = blocks.any { word in it.text }
}

private const val EXTENSION = ".md"
private val Approvals = listOf("Aprobat:", "Approved:")
private val StepLine = Regex("""(\d+)\.\s+(.+)""")

/** English has its own copy of the sheets, with the same file names; every other language reads the Romanian originals. */
fun sheetDirectory(language: String): String = if (language == "en") "firstaid-en" else "firstaid"

/** The language of an event pack's sheets: the format is the one of the Romanian originals. */
private const val PACK_LANGUAGE = "ro"

/**
 * The sheet files to show in [language]: the event pack's ([pack]), approved by its medical lead, where they are in
 * that language; elsewhere the app's own copy in it ([bundled]), shown as a draft until approved, rather than sheets
 * the person can't read. The pack's sheets when the app has none of its own.
 */
fun sheetFiles(language: String, pack: Map<String, String>, bundled: () -> Map<String, String>): Map<String, String> =
    if (pack.isNotEmpty() && language == PACK_LANGUAGE) pack else bundled().ifEmpty { pack }

/** The sheets to show in [language], in file-name order. */
fun loadSheets(session: PingUpSession, language: String): List<FirstAidSheet> {
    val files = sheetFiles(language, session.event?.firstAid.orEmpty()) { session.assets(sheetDirectory(language)) }
    return readSheets(files.keys.toList(), files::getValue)
}

fun readSheets(names: List<String>, read: (String) -> String): List<FirstAidSheet> =
    names.filter { it.endsWith(EXTENSION) }.sorted().map { parseSheet(it.removeSuffix(EXTENSION), read(it)) }

/**
 * A small Markdown subset: "# " starts the title, "1. " a step, "! " a warning and "Aprobat: " ("Approved: " in English) the approval line;
 * any other line is text. A line right under a block continues it; an empty line ends it.
 */
fun parseSheet(id: String, text: String): FirstAidSheet {
    var title = ""
    var approval: String? = null
    val blocks = mutableListOf<SheetBlock>()
    var open = false
    for (raw in text.lines()) {
        val line = raw.trim()
        val step = StepLine.matchEntire(line)
        open = when {
            line.isEmpty() -> false
            line.startsWith("# ") -> {
                title = line.removePrefix("# ").trim()
                false
            }
            Approvals.any(line::startsWith) -> {
                approval = line.substringAfter(':').trim().ifEmpty { null }
                false
            }
            step != null -> blocks.add(SheetBlock.Step(step.groupValues[1].toInt(), step.groupValues[2]))
            line.startsWith("! ") -> blocks.add(SheetBlock.Warning(line.removePrefix("! ").trim()))
            open -> {
                blocks[blocks.lastIndex] = blocks.last().let { it.with("${it.text} $line") }
                true
            }
            else -> blocks.add(SheetBlock.Text(line))
        }
    }
    return FirstAidSheet(id, title, blocks, approval)
}

private fun SheetBlock.with(text: String): SheetBlock = when (this) {
    is SheetBlock.Text -> copy(text = text)
    is SheetBlock.Step -> copy(text = text)
    is SheetBlock.Warning -> copy(text = text)
}
