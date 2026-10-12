package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.place_accuracy
import ro.safetyplease.ui.resources.place_near
import ro.safetyplease.ui.text.fill

/**
 * Where a team finds the reporter, as said on the radio: "D4, lângă Toalete camping (±12 m)". Leaves out what isn't
 * known; empty when nothing is.
 */
fun placeLine(square: String, landmark: String, accuracyM: Int, near: (String) -> String, accuracy: (Int) -> String): String {
    val where = listOf(square, if (landmark.isEmpty()) "" else near(landmark)).filter { it.isNotEmpty() }.joinToString(", ")
    return when {
        accuracyM <= 0 -> where
        where.isEmpty() -> accuracy(accuracyM)
        else -> "$where (${accuracy(accuracyM)})"
    }
}

/** [placeLine] of a report: its grid square, its landmark by number in [venue] and its accuracy. */
@Composable
fun placeText(square: String, landmark: Int, accuracyM: Int, venue: Venue): String {
    val near = stringResource(Res.string.place_near)
    val accuracy = stringResource(Res.string.place_accuracy)
    return placeLine(square, venue.landmark(landmark)?.name.orEmpty(), accuracyM, { near.fill(it) }, { accuracy.fill(it) })
}
