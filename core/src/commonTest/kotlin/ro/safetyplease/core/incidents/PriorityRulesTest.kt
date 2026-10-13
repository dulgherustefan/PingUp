package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PriorityRulesTest {
    private val shipped = PriorityRules.parse(repoFile("app/src/main/assets/priority_rules.json").readText())

    private fun incident(
        category: Int = IncidentCategory.MEDICAL,
        responds: Int = Answer.UNASKED,
        breathes: Int = Answer.UNASKED,
        description: String = "",
        unconscious: Boolean = false,
        manDown: Boolean = false,
        cannotMove: Boolean = false,
    ) = StaffIncident(
        incidentId = "a", category = category, severity = Severity.MEDIUM, zone = "", description = description, reportedAt = 0,
        receivedAt = 0, hops = 1, responds = responds, breathes = breathes, unconscious = unconscious, manDown = manDown,
        cannotMove = cannotMove,
    )

    private fun rule(incident: StaffIncident, temperature: Double? = null, rules: PriorityRules = shipped) = rules.match(Signs.of(incident, temperature))?.id

    @Test
    fun theShippedTableIsReadAndApproved() {
        assertTrue(shipped !== PriorityRules.FALLBACK, "the asset parses")
        assertTrue(shipped.table.rules.size >= PriorityRules.FALLBACK.table.rules.size)
        assertEquals("", shipped.table.approved, "nobody has signed it off yet: the medical lead does")
    }

    @Test
    fun triageAnswersAndFlagsMakeItTopPriority() {
        assertEquals("not_breathing", rule(incident(breathes = Answer.NO)))
        assertEquals("not_breathing", rule(incident(responds = Answer.NO, breathes = Answer.NO)), "the first rule that holds wins")
        assertEquals("unresponsive", rule(incident(responds = Answer.NO, breathes = Answer.YES)))
        assertEquals("unconscious", rule(incident(unconscious = true)))
        assertEquals("man_down", rule(incident(category = IncidentCategory.VIOLENCE, manDown = true)))
        assertNull(rule(incident(responds = Answer.UNKNOWN, breathes = Answer.UNKNOWN)), "a \"don't know\" is not a no")
        assertNull(rule(incident(category = IncidentCategory.CROWD, cannotMove = true)), "stuck in the crowd feeds the crowd signal instead")
    }

    @Test
    fun wordsMatchWithoutCaseOrDiacriticsAndOnlyInTheirCategories() {
        assertEquals("seizure", rule(incident(description = "Are CONVULSII lângă bar")))
        assertEquals("hot_skin", rule(incident(description = "pielea e foarte fierbinte, transpiră")))
        assertEquals("confusion", rule(incident(description = "pare confuză")))
        assertEquals("heavy_bleeding", rule(incident(category = IncidentCategory.VIOLENCE, description = "Sângerează mult la cap")))
        assertNull(rule(incident(category = IncidentCategory.FIRE, description = "convulsii")), "a medical sign counts in a medical report")
        assertNull(rule(incident(description = "i-e rău, a băut mult")))
    }

    @Test
    fun aTemperatureNotedOnTheCardRaisesItOnTheConsole() {
        assertNull(rule(incident(), temperature = 39.4))
        assertEquals("high_temperature", rule(incident(), temperature = 40.0))
        assertNull(rule(incident(category = IncidentCategory.OTHER), temperature = 41.0))
    }

    @Test
    fun aMissingOrBrokenTableFallsBackToTheTriageRules() {
        assertSame(PriorityRules.FALLBACK, PriorityRules.parse(null))
        assertSame(PriorityRules.FALLBACK, PriorityRules.parse("{ not json"))
        assertSame(PriorityRules.FALLBACK, PriorityRules.parse("""{"rules": [{"id": "empty"}]}"""), "a rule naming nothing matches nothing")
        assertEquals("not_breathing", rule(incident(breathes = Answer.NO), rules = PriorityRules.FALLBACK))
        assertNull(rule(incident(description = "convulsii"), rules = PriorityRules.FALLBACK))
        val custom = PriorityRules.parse("""{"version": 2, "rules": [{"id": "heat", "categories": [1], "minTemperatureC": 39.5, "extra": 1}]}""")
        assertEquals("heat", rule(incident(), 39.5, custom), "unknown keys are ignored")
    }
}
