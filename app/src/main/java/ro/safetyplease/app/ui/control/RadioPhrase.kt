package ro.safetyplease.app.ui.control

import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.incidents.Recognition
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
    /** "lângă Toalete camping". */
    val near: (String) -> String,
    /** "raportor cu tricou roșu", from what the reporter said to look for. */
    val recognition: (String) -> String,
)

private val Spaces = Regex("\\s+")

/**
 * One line to read on the radio, in ETHANE order as far as the report goes: what happened and how urgent, the triage
 * answers, where (grid square, nearest [landmark] and zone), how to recognise the reporter, how many people and whether
 * the danger goes on, then the reporter's own words.
 * "Medical, urgent, răspunde: nu, respiră normal: nu știu, E2 lângă Toalete camping, Scena principală, raportor cu
 * tricou roșu, a leșinat lângă turnul de lumini."
 */
internal fun radioPhrase(report: LogEntry, zoneName: String, landmark: String, words: RadioWords): String {
    val spot = listOf(report.grid, if (landmark.isBlank()) "" else words.near(landmark)).filter { it.isNotBlank() }.joinToString(" ")
    val place = listOf(spot, zoneName).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { words.unknownPlace }
    val (hint, text) = Recognition.split(report.description)
    val said = text.replace(Spaces, " ").trim().trimEnd('.', '!', '?', ',', ';', ':', ' ')
    val urgency = if (report.severity == Severity.URGENT) words.urgent else words.notUrgent
    val asked = report.responds != Answer.UNASKED || report.breathes != Answer.UNASKED
    return listOf(
        words.category(report.category), urgency, if (asked) words.triage(report.responds, report.breathes) else "", place,
        if (hint.isEmpty()) "" else words.recognition(hint),
        if (report.people > 0) words.people(report.people) else "", if (report.dangerContinues) words.dangerContinues else "", said,
    ).filter { it.isNotEmpty() }.joinToString(", ") + "."
}
