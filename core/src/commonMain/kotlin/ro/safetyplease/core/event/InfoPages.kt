package ro.safetyplease.core.event

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ro.safetyplease.core.util.utf8

sealed interface PageBlock {
    val text: String

    data class Paragraph(override val text: String) : PageBlock
    data class Heading(override val text: String) : PageBlock
    data class Bullet(override val text: String) : PageBlock
    data class Warning(override val text: String) : PageBlock
}

/**
 * A static information page (PROTOCOL.md, section 16). [approval] names who at the organiser approved the text and
 * when; until then it is null and the page shows as a draft.
 */
data class InfoPage(val id: String, val title: String, val blocks: List<PageBlock>, val approval: String?) {
    /** The first paragraph: what the page is for. */
    val summary: String get() = blocks.firstOrNull { it is PageBlock.Paragraph }?.text.orEmpty()

    val draft: Boolean get() = approval == null
}

object InfoPages {
    const val MAX_TOTAL_BYTES = 200 * 1024
    const val MAX_PAGE_BYTES = 16 * 1024
    const val MAX_PAGES = 24
    private const val MAX_NAME = 64
    private const val EXTENSION = ".md"
    private const val FALLBACK = "en"
    private val LANGUAGE = Regex("[a-z]{2}")
    private val FILE = Regex("[A-Za-z0-9._-]+")
    private val APPROVALS = listOf("Aprobat:", "Approved:")

    /**
     * A small Markdown subset: "# " is the title, "## " a heading, "- " a bullet, "! " a warning and "Aprobat: "
     * ("Approved: " in English) the approval line; any other line is a paragraph. A line right under a block continues
     * it; an empty line ends it. Null without a title.
     */
    fun parse(id: String, text: String): InfoPage? {
        var title = ""
        var approval: String? = null
        val blocks = mutableListOf<PageBlock>()
        var open = false
        for (raw in text.lines()) {
            val line = raw.trim()
            open = when {
                line.isEmpty() -> false
                line.startsWith("# ") -> {
                    title = line.removePrefix("# ").trim()
                    false
                }
                APPROVALS.any(line::startsWith) -> {
                    approval = line.substringAfter(':').trim().ifEmpty { null }
                    false
                }
                line.startsWith("## ") -> {
                    blocks.add(PageBlock.Heading(line.removePrefix("## ").trim()))
                    false
                }
                line.startsWith("- ") -> blocks.add(PageBlock.Bullet(line.removePrefix("- ").trim()))
                line.startsWith("! ") -> blocks.add(PageBlock.Warning(line.removePrefix("! ").trim()))
                open -> {
                    blocks[blocks.lastIndex] = blocks.last().continued(line)
                    true
                }
                else -> blocks.add(PageBlock.Paragraph(line))
            }
        }
        return if (title.isEmpty()) null else InfoPage(id, title, blocks, approval)
    }

    /** The pages of [files] (file name to text), in file-name order; files that aren't pages are left out. */
    fun read(files: Map<String, String>): List<InfoPage> =
        files.keys.filter { it.endsWith(EXTENSION) }.sorted().mapNotNull { parse(it.removeSuffix(EXTENSION), files.getValue(it)) }

    /** The files for [language], else English, else whatever language the pack has. */
    fun forLanguage(pages: Map<String, Map<String, String>>, language: String): Map<String, String> =
        pages[language] ?: pages[FALLBACK] ?: pages.values.firstOrNull().orEmpty()

    /**
     * The pack's `pages` field: `{"en": {"01-beach.md": "# Beach..."}}`, a language code and then file names to
     * text. Absent is no pages; null is a field no phone should trust: wrong shapes, bad names, a page without a title,
     * too many pages or more than [MAX_TOTAL_BYTES] of text in all.
     */
    fun fromJson(element: JsonElement?): Map<String, Map<String, String>>? {
        if (element == null) return emptyMap()
        val languages = element as? JsonObject ?: return null
        var total = 0
        val result = mutableMapOf<String, Map<String, String>>()
        for ((language, files) in languages) {
            if (!LANGUAGE.matches(language)) return null
            val byFile = files as? JsonObject ?: return null
            if (byFile.size > MAX_PAGES) return null
            val texts = mutableMapOf<String, String>()
            for ((name, value) in byFile) {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
                if (name.length > MAX_NAME || !name.endsWith(EXTENSION) || !FILE.matches(name)) return null
                val bytes = text.utf8().size
                if (bytes > MAX_PAGE_BYTES || parse(name.removeSuffix(EXTENSION), text) == null) return null
                total += bytes
                texts[name] = text
            }
            result[language] = texts
        }
        return if (total > MAX_TOTAL_BYTES) null else result
    }

    private fun PageBlock.continued(line: String): PageBlock {
        val joined = "$text $line"
        return when (this) {
            is PageBlock.Paragraph -> copy(text = joined)
            is PageBlock.Heading -> copy(text = joined)
            is PageBlock.Bullet -> copy(text = joined)
            is PageBlock.Warning -> copy(text = joined)
        }
    }
}
