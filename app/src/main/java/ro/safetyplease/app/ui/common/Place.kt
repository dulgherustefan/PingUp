package ro.safetyplease.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import ro.safetyplease.app.R
import ro.safetyplease.core.venue.Venue

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
    val res = LocalResources.current
    return placeLine(
        square, venue.landmark(landmark)?.name.orEmpty(), accuracyM,
        { res.getString(R.string.place_near, it) }, { res.getString(R.string.place_accuracy, it) },
    )
}
