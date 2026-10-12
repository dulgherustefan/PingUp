package ro.safetyplease.app.ui.common

import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.incident_danger_continues
import ro.safetyplease.ui.resources.incident_from_staff
import ro.safetyplease.ui.resources.incident_people
import ro.safetyplease.ui.resources.incident_triage
import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.text.Labels
import ro.safetyplease.core.protocol.Answer

/**
 * What a report says beyond its words, in one line: the triage answers as given, "don't know" included, and for a
 * staff report how many people and whether the danger goes on. Null when it says nothing more.
 */
@Composable
fun reportFacts(responds: Int, breathes: Int, fromStaff: Boolean, people: Int, dangerContinues: Boolean): String? {
    val parts = buildList {
        if (responds != Answer.UNASKED || breathes != Answer.UNASKED) {
            add(stringResource(Res.string.incident_triage, stringResource(Labels.answer(responds)), stringResource(Labels.answer(breathes))))
        }
        if (fromStaff) add(stringResource(Res.string.incident_from_staff))
        if (people > 0) add(pluralStringResource(Res.plurals.incident_people, people, people))
        if (dangerContinues) add(stringResource(Res.string.incident_danger_continues))
    }
    return parts.joinToString(" · ").ifEmpty { null }
}
