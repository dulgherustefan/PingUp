package ro.safetyplease.app.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/** values/ (Romanian) and values-en/ must offer the same texts, with the same placeholders. */
class TranslationTest {
    private class Resources(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val arrays: Map<String, List<String>>) {
        val keys: Set<String> get() = strings.keys.map { "string/$it" }.toSet() + plurals.keys.map { "plurals/$it" } + arrays.keys.map { "array/$it" }
    }

    private fun load(path: String): Resources {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        fun children(parent: Element, tag: String) =
            (0 until parent.childNodes.length).map { parent.childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
        fun translatable(e: Element) = e.getAttribute("translatable") != "false"
        return Resources(
            children(root, "string").filter(::translatable).associate { it.getAttribute("name") to it.textContent },
            children(root, "plurals").associate { p -> p.getAttribute("name") to children(p, "item").associate { it.getAttribute("quantity") to it.textContent } },
            children(root, "string-array").filter(::translatable).associate { a -> a.getAttribute("name") to children(a, "item").map { it.textContent } },
        )
    }

    // the shared texts (in :ui, for both apps) and the debug build's own
    private val sets = listOf("../ui/src/commonMain/composeResources", "src/debug/res").map { load("$it/values/strings.xml") to load("$it/values-en/strings.xml") }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""").findAll(text).map { it.value }.sorted().toList()

    @Test
    fun everyKeyIsInBothLanguages() {
        for ((ro, en) in sets) {
            assertEquals("missing in English", emptySet<String>(), ro.keys - en.keys)
            assertEquals("missing in Romanian", emptySet<String>(), en.keys - ro.keys)
        }
    }

    @Test
    fun placeholdersMatch() {
        for ((ro, en) in sets) {
            for ((key, text) in ro.strings) assertEquals(key, placeholders(text), placeholders(en.strings.getValue(key)))
            for ((key, items) in ro.plurals) {
                val expected = placeholders(items.getValue("other"))
                assertEquals(key, expected, placeholders(en.plurals.getValue(key).getValue("other")))
                for ((quantity, text) in en.plurals.getValue(key)) {
                    assertTrue("$key/$quantity", expected.containsAll(placeholders(text)))
                }
            }
            for ((key, items) in ro.arrays) assertEquals(key, items.size, en.arrays.getValue(key).size)
        }
    }

    @Test
    fun sharedTextsReadTheSameInBothApps() {
        // Compose Multiplatform fills only %1$s and %1$d, and shows an escape Android needs, like \', as it is
        val (ro, en) = sets.first()
        for (texts in listOf(ro, en)) {
            val all = texts.strings.entries.map { it.key to it.value } +
                texts.plurals.flatMap { (key, items) -> items.values.map { key to it } } +
                texts.arrays.flatMap { (key, items) -> items.map { key to it } }
            for ((key, text) in all) {
                assertTrue("$key: $text", placeholders(text).all { Regex("""%\d+\$[sd]""").matches(it) })
                assertTrue("$key: $text", "\\'" !in text && "\\\"" !in text)
            }
        }
    }

    @Test
    fun pluralsUseEachLanguagesQuantities() {
        for ((ro, en) in sets) {
            for ((key, items) in ro.plurals) assertEquals(key, setOf("one", "few", "other"), items.keys)
            for ((key, items) in en.plurals) assertEquals(key, setOf("one", "other"), items.keys)
        }
    }

    @Test
    fun englishHasNoRomanianLeftOver() {
        val romanian = Regex("[ăâîșțşţĂÂÎȘȚŞŢ„]")
        // the authors' names stay as in their IDs, in English too
        val names = listOf("Ștefan", "Cîrciumaru", "Farkaș")
        for ((_, en) in sets) {
            val texts = en.strings.entries.map { it.key to it.value } +
                en.plurals.flatMap { (key, items) -> items.values.map { key to it } } +
                en.arrays.flatMap { (key, items) -> items.map { key to it } }
            for ((key, text) in texts) {
                val words = names.fold(text) { left, name -> left.replace(name, "") }
                assertTrue("$key: $text", !romanian.containsMatchIn(words))
            }
        }
    }

    @Test
    fun datePatternsFollowTheLanguage() {
        val (ro, en) = sets.first()
        val time = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 12, 14, 30) }.time
        fun format(res: Resources, key: String, locale: Locale) = SimpleDateFormat(res.strings.getValue(key), locale).format(time)
        val romanian = Locale.forLanguageTag("ro-RO")
        assertEquals("12 octombrie, 14:30", format(ro, "date_pattern_full", romanian))
        assertEquals("October 12, 14:30", format(en, "date_pattern_full", Locale.US))
        assertEquals("Monday, October 12", format(en, "date_pattern_day", Locale.US))
        assertEquals("luni, 12 octombrie", format(ro, "date_pattern_day", romanian))
        assertEquals("Oct 12", format(en, "date_pattern_short", Locale.US))
        assertEquals("Oct 12, 14:30", format(en, "date_pattern_recent", Locale.US))
    }
}
