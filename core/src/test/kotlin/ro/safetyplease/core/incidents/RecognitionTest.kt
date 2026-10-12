package ro.safetyplease.core.incidents

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionTest {
    @Test
    fun hintGoesOnTheFirstLineAndComesBack() {
        val description = Recognition.join(" tricou\n roșu ", "a leșinat\nlângă bar")
        assertEquals("${Recognition.MARK}tricou roșu\na leșinat\nlângă bar", description)
        assertEquals("tricou roșu" to "a leșinat\nlângă bar", Recognition.split(description))
    }

    @Test
    fun hintAloneOrNoHint() {
        assertEquals("steag galben" to "", Recognition.split(Recognition.join("steag galben", "")))
        assertEquals("a leșinat", Recognition.join("  ", "a leșinat"))
        assertEquals("" to "a leșinat", Recognition.split("a leșinat"))
    }

    @Test
    fun longHintIsCutWithoutSplittingACharacter() {
        val hint = "a".repeat(Recognition.MAX_CHARS - 1) + "👀"
        val (line, _) = Recognition.split(Recognition.join(hint, "x"))
        assertEquals("a".repeat(Recognition.MAX_CHARS - 1), line)
    }
}
