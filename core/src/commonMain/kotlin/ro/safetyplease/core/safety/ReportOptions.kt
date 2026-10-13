package ro.safetyplease.core.safety

import ro.safetyplease.core.protocol.ReportExtras

/**
 * What a person adds to a report about harassment or feeling unsafe (PROTOCOL.md, section 6.3): that it happened
 * earlier, that nothing can be answered because the report is anonymous, that it happened to someone else.
 */
object ReportOptions {
    /** How far back "s-a întâmplat mai devreme" goes, in minutes. */
    val laterChoices = listOf(30, 60, 120, 180, 360, 720, ReportExtras.MAX_HAPPENED_MIN)

    /** [minutes] if a report can say it happened that long before it; null for now or for longer than a day. */
    fun later(minutes: Int): Int? = minutes.takeIf { it in 1..ReportExtras.MAX_HAPPENED_MIN }

    /** The extension block for what the person chose; a time out of bounds is left out, as if not chosen. */
    fun extras(laterMin: Int? = null, anonymous: Boolean = false, witness: Boolean = false): ReportExtras =
        ReportExtras(happenedMinAgo = laterMin?.let(::later) ?: 0, anonymous = anonymous, witness = witness)
}
