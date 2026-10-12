package ro.safetyplease.ui.text

import kotlinx.coroutines.runBlocking
import ro.safetyplease.core.protocol.IncidentCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class TextsTest {
    @Test
    fun anyCodeReadsAsTheTextItsLabelGivesIt() = runBlocking {
        val names = textsOf(IncidentCategory.all, Labels::category) { it.key }
        assertEquals("cat_medical", names(IncidentCategory.MEDICAL))
        // a code from a newer phone reads as its label's fallback, not as nothing
        assertEquals("cat_other", names(99))
    }
}
