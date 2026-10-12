package ro.safetyplease.core.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoPagesTest {
    private val sample = """
        # Beach

        Flags and lifeguards
        on the beach.

        ## Flags
        - Green: swimming allowed.
        - Red: no swimming,
          even to wade in.
        ! Never swim at night.
        ## Hours
        Lifeguards: (source: organiser).
    """.trimIndent()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun parsesTitleParagraphsHeadingsBulletsAndWarnings() {
        val page = InfoPages.parse("01-beach", sample)!!
        assertEquals("Beach", page.title)
        assertEquals("a line right under a block continues it", "Flags and lifeguards on the beach.", page.summary)
        assertEquals(
            listOf(
                PageBlock.Paragraph("Flags and lifeguards on the beach."),
                PageBlock.Heading("Flags"),
                PageBlock.Bullet("Green: swimming allowed."),
                PageBlock.Bullet("Red: no swimming, even to wade in."),
                PageBlock.Warning("Never swim at night."),
                PageBlock.Heading("Hours"),
                PageBlock.Paragraph("Lifeguards: (source: organiser)."),
            ),
            page.blocks,
        )
    }

    @Test
    fun aPageWithoutATitleIsNotAPage() {
        assertNull(InfoPages.parse("a", "Just text"))
        assertNull(InfoPages.parse("a", "#\nText"))
    }

    @Test
    fun withoutAnApprovalLineThePageIsADraft() {
        assertTrue(InfoPages.parse("a", sample)!!.draft)
        val approved = InfoPages.parse("a", "$sample\n\nApproved: Ana Pop, safety lead, 12.06.2027")!!
        assertEquals("Ana Pop, safety lead, 12.06.2027", approved.approval)
        assertFalse(approved.draft)
        assertEquals("Ana Pop", InfoPages.parse("a", "$sample\n\nAprobat: Ana Pop")!!.approval)
        assertTrue("an empty approval approves nothing", InfoPages.parse("a", "$sample\n\nAprobat:")!!.draft)
    }

    @Test
    fun readKeepsPagesInFileNameOrder() {
        val files = mapOf("02-b.md" to "# B\n\nb", "01-a.md" to "# A\n\na", "notes.txt" to "# X", "03-c.md" to "no title")
        assertEquals(listOf("01-a", "02-b"), InfoPages.read(files).map { it.id })
    }

    @Test
    fun aPhoneReadsItsLanguageThenEnglishThenWhatTheresIs() {
        val pages = mapOf("ro" to mapOf("a.md" to "# A"), "en" to mapOf("b.md" to "# B"), "de" to mapOf("c.md" to "# C"))
        assertEquals(setOf("a.md"), InfoPages.forLanguage(pages, "ro").keys)
        assertEquals(setOf("c.md"), InfoPages.forLanguage(pages, "de").keys)
        assertEquals(setOf("b.md"), InfoPages.forLanguage(pages, "fr").keys)
        assertEquals(setOf("a.md"), InfoPages.forLanguage(mapOf("ro" to mapOf("a.md" to "# A")), "en").keys)
        assertTrue(InfoPages.forLanguage(emptyMap(), "en").isEmpty())
    }

    @Test
    fun theFieldIsOptionalAndEveryMalformedShapeIsRefused() {
        assertEquals(emptyMap<String, Map<String, String>>(), InfoPages.fromJson(null))
        assertEquals(emptyMap<String, Map<String, String>>(), InfoPages.fromJson(json("{}")))
        val ok = InfoPages.fromJson(json("""{"en":{"01-a.md":"# A\nText"}}"""))
        assertEquals(mapOf("en" to mapOf("01-a.md" to "# A\nText")), ok)
        val refused = listOf(
            """{"en":[]}""",
            """{"en":{"a.md":["# A"]}}""",
            """{"en":{"a.md":"text without title"}}""",
            """{"en":{"a.txt":"# A"}}""",
            """{"en":{"a b.md":"# A"}}""",
            """{"en":{"${"x".repeat(62)}.md":"# A"}}""",
            """{"EN":{"a.md":"# A"}}""",
            """{"eng":{"a.md":"# A"}}""",
        )
        for (field in refused) assertNull(field, InfoPages.fromJson(json(field)))
    }

    @Test
    fun sizeLimitsHoldPerPageInTotalAndInCount() {
        val line = "x".repeat(1000)
        // the JSON text needs the escape, not the newline itself
        fun page(kb: Int) = "# T\\n" + "$line\\n".repeat(kb)
        fun pages(count: Int, kb: Int) = json("""{"en":{${(1..count).joinToString(",") { """"$it.md":"${page(kb)}"""" }}}}""")
        assertNull("one page past 16 KiB", InfoPages.fromJson(pages(1, 17)))
        assertEquals(1, InfoPages.fromJson(pages(1, 15))!!.getValue("en").size)
        assertEquals("195 KB in all", 13, InfoPages.fromJson(pages(13, 15))!!.getValue("en").size)
        assertNull("210 KB in all", InfoPages.fromJson(pages(14, 15)))
        assertNull("too many pages", InfoPages.fromJson(pages(InfoPages.MAX_PAGES + 1, 0)))
        assertEquals(InfoPages.MAX_PAGES, InfoPages.fromJson(pages(InfoPages.MAX_PAGES, 0))!!.getValue("en").size)
    }
}
