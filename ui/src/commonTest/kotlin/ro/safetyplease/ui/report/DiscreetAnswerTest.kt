package ro.safetyplease.ui.report

import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.safety.Welfare
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscreetAnswerTest {
    private fun report(description: String) =
        MyReport(incidentId = "a1", category = IncidentCategory.HARASSMENT, severity = Severity.MEDIUM, zone = "", createdAt = 0, description = description)

    @Test
    fun theScreenStaysStillWhenADiscreetReportIsAnswered() {
        assertFalse(nudgesOnAnswer(report(Discreet.describe("x"))))
        assertFalse(nudgesOnAnswer(report(Welfare.describe("y"))), "Nu sunt bine is discreet too")
        assertTrue(nudgesOnAnswer(report("Cineva a leșinat")))
    }
}
