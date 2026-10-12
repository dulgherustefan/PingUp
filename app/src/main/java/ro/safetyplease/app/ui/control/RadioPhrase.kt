package ro.safetyplease.app.ui.control

import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.Severity

/** The words of the radio phrase, from strings.xml. */
internal class RadioWords(
    val category: (Int) -> String,
    val urgent: String,
    val notUrgent: String,
    val unknownPlace: String,
    /** "răspunde: nu, respiră normal: nu știu", from the two triage answers. */
    val triage: (responds: Int, breathes: Int) -> String,
    val people: (Int) -> String,
    val dangerContinues: String,
)

private val Spaces = Regex("\\s+")

/**
 * One line to read on the radio, in ETHANE order as far as the report goes: what happened and how urgent, the triage
 * answers, where (grid square and zone), how many people and whether the danger goes on, then the reporter's own words,
 * which usually say how to find them.
 * "Medical, urgent, răspunde: nu, respiră normal: nu știu, E2, Scena principală, a leșinat lângă turnul de lumini."
 */
internal fun radioPhrase(report: LogEntry, zoneName: String, words: RadioWords): String {
    val place = listOf(report.grid, zoneName).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { words.unknownPlace }
    val said = report.description.replace(Spaces, " ").trim().trimEnd('.', '!', '?', ',', ';', ':', ' ')
    val urgency = if (report.severity == Severity.URGENT) words.urgent else words.notUrgent
    val asked = report.responds != Answer.UNASKED || report.breathes != Answer.UNASKED
    return listOf(
        words.category(report.category), urgency, if (asked) words.triage(report.responds, report.breathes) else "", place,
        if (report.people > 0) words.people(report.people) else "", if (report.dangerContinues) words.dangerContinues else "", said,
    ).filter { it.isNotEmpty() }.joinToString(", ") + "."
}
