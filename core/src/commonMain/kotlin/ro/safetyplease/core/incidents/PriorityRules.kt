package ro.safetyplease.core.incidents

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.util.decomposed

/**
 * One line of the medical lead's table: an incident is top priority when every sign the rule names is there. A rule
 * that names nothing never matches. [responds] and [breathes] are [Answer] codes; [flags] and [words] match if any of
 * theirs does; a word matches anywhere in the description, without case or diacritics, so "convuls" finds "convulsii".
 */
@Serializable
data class PriorityRule(
    val id: String,
    /** Incident categories it holds for; empty for all. */
    val categories: List<Int> = emptyList(),
    val responds: Int? = null,
    val breathes: Int? = null,
    val flags: List<String> = emptyList(),
    val words: List<String> = emptyList(),
    /** The hand-over card's latest temperature at least this, in °C. */
    val minTemperatureC: Double? = null,
) {
    val empty: Boolean get() = responds == null && breathes == null && flags.isEmpty() && words.isEmpty() && minTemperatureC == null
}

/** The table as the app ships it (`priority_rules.json`); [approved] says who signed it off, empty until someone does. */
@Serializable
data class PriorityTable(val version: Int = 1, val approved: String = "", val rules: List<PriorityRule> = emptyList())

/** What an incident shows, as the rules read it. */
data class Signs(
    val category: Int,
    val responds: Int = Answer.UNASKED,
    val breathes: Int = Answer.UNASKED,
    val flags: Set<String> = emptySet(),
    val text: String = "",
    val temperatureC: Double? = null,
) {
    companion object {
        const val UNCONSCIOUS = "unconscious"
        const val MAN_DOWN = "man_down"
        const val CANNOT_MOVE = "cannot_move"

        fun of(incident: StaffIncident, temperatureC: Double? = null) = Signs(
            incident.category, incident.responds, incident.breathes,
            buildSet {
                if (incident.unconscious) add(UNCONSCIOUS)
                if (incident.manDown) add(MAN_DOWN)
                if (incident.cannotMove) add(CANNOT_MOVE)
            },
            incident.description, temperatureC,
        )
    }
}

/**
 * Raises an incident to the top of the queue and to urgent on every phone that opens it (roadmap row 8). The rules
 * are data: the medical lead changes the table, not the code.
 */
class PriorityRules(val table: PriorityTable) {
    private val words = table.rules.associate { rule -> rule.id to rule.words.map(::plain).filter { it.isNotEmpty() } }

    /** The first rule that holds, or null. */
    fun match(signs: Signs): PriorityRule? {
        val text = plain(signs.text)
        return table.rules.firstOrNull { rule ->
            !rule.empty &&
                (rule.categories.isEmpty() || signs.category in rule.categories) &&
                (rule.responds == null || signs.responds == rule.responds) &&
                (rule.breathes == null || signs.breathes == rule.breathes) &&
                (rule.flags.isEmpty() || rule.flags.any { it in signs.flags }) &&
                (rule.words.isEmpty() || words.getValue(rule.id).any { it in text }) &&
                (rule.minTemperatureC == null || (signs.temperatureC ?: Double.NEGATIVE_INFINITY) >= rule.minTemperatureC)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val MARKS = setOf(CharCategory.NON_SPACING_MARK, CharCategory.COMBINING_SPACING_MARK, CharCategory.ENCLOSING_MARK)

        /** A build without the table still puts a person who doesn't breathe or respond first. */
        val FALLBACK = PriorityRules(
            PriorityTable(
                rules = listOf(
                    PriorityRule("not_breathing", breathes = Answer.NO),
                    PriorityRule("unconscious", flags = listOf(Signs.UNCONSCIOUS)),
                    PriorityRule("unresponsive", responds = Answer.NO),
                    PriorityRule("man_down", flags = listOf(Signs.MAN_DOWN)),
                ),
            ),
        )

        /** The shipped table; [FALLBACK] if it is missing or can't be read. */
        fun parse(text: String?): PriorityRules =
            text?.let { runCatching { PriorityRules(json.decodeFromString(PriorityTable.serializer(), it)) }.getOrNull() }
                ?.takeIf { rules -> rules.table.rules.any { !it.empty } } ?: FALLBACK

        private fun plain(text: String): String = text.lowercase().decomposed().filterNot { it.category in MARKS }
    }
}
