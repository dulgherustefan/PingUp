package ro.safetyplease.app.ui.control

import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.protocol.Severity

/** The words of the radio phrase, from strings.xml. */
internal class RadioWords(
    val category: (Int) -> String,
    val urgent: String,
    val notUrgent: String,
    val unknownPlace: String,
)

private val Spaces = Regex("\\s+")

/**
 * One line to read on the radio, in ETHANE order as far as today's report goes: what happened and how urgent,
 * where (grid square and zone), then the reporter's own words, which usually say how to find them.
 * "Medical, urgent, E2, Scena principală, a leșinat lângă turnul de lumini, tricou roșu."
 */
internal fun radioPhrase(report: LogEntry, zoneName: String, words: RadioWords): String {
    val place = listOf(report.grid, zoneName).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { words.unknownPlace }
    val said = report.description.replace(Spaces, " ").trim().trimEnd('.', '!', '?', ',', ';', ':', ' ')
    val urgency = if (report.severity == Severity.URGENT) words.urgent else words.notUrgent
    return listOf(words.category(report.category), urgency, place, said).filter { it.isNotEmpty() }.joinToString(", ") + "."
}
