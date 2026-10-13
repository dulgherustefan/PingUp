package ro.safetyplease.core.incidents

import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.protocol.Triage

/** How a report is handled from what the reporter said: the category alone no longer makes it urgent. */
object Priority {
    /** Categories where a wrong cancel costs little, so the reporter's cancel closes the incident by itself. */
    private val selfCancelling = setOf(IncidentCategory.CROWD, IncidentCategory.OTHER, IncidentCategory.LOST_FRIEND)

    /**
     * A lost child is always urgent and a lost friend never is. Otherwise a "no" to a triage question or the reporter's
     * own [urgent] makes the report urgent; anything else, a "don't know" included, stays medium.
     */
    fun severity(category: Int, urgent: Boolean, triage: Triage? = null): Int = when {
        category == IncidentCategory.LOST_CHILD -> Severity.URGENT
        category == IncidentCategory.LOST_FRIEND -> Severity.LOW
        urgent || triage?.critical == true -> Severity.URGENT
        else -> Severity.MEDIUM
    }

    /** For the control room only: no alert, and not on the list of the teams in the field. */
    fun isInformational(category: Int): Boolean = category == IncidentCategory.LOST_FRIEND

    /**
     * The reporter's cancel only asks an operator to check: someone may have taken the phone of a person who still
     * needs help. Urgent reports and categories about a person at risk; a category from a newer version too.
     */
    fun cancelNeedsReview(category: Int, severity: Int): Boolean = severity == Severity.URGENT || category !in selfCancelling
}
