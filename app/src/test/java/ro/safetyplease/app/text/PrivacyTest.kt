package ro.safetyplease.app.text

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The policy in the app (Eu › Confidențialitate) and PRIVACY.md, the page Play links to, must say the same. */
class PrivacyTest {
    private fun privacyTexts(path: String): List<String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement
        return (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>()
            .filter { it.tagName == "string" && it.getAttribute("name").startsWith("privacy_") }
            .map { it.textContent.replace("\\'", "'").replace("\\\"", "\"") }
    }

    private val appTexts get() = privacyTexts("../ui/src/commonMain/composeResources/values/strings.xml") +
        privacyTexts("../ui/src/commonMain/composeResources/values-en/strings.xml")

    @Test
    fun theRepositoryPolicySaysWhatTheAppSays() {
        // links read as their text, as in the app
        val policy = File("../PRIVACY.md").readText().replace(Regex("""\[([^]]+)]\([^)]+\)"""), "$1")
        val texts = appTexts
        assertTrue("no privacy texts", texts.isNotEmpty())
        for (text in texts) assertTrue(text, text in policy)
    }

    @Test
    fun theAppHasEverySectionOfThePolicy() {
        val headings = File("../PRIVACY.md").readLines().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
        val texts = appTexts
        assertTrue("no sections", headings.isNotEmpty())
        for (heading in headings) assertTrue(heading, heading in texts)
    }
}
