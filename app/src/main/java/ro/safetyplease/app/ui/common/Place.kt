package ro.safetyplease.app.ui.common

import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.place_accuracy
import ro.safetyplease.ui.resources.place_near
import ro.safetyplease.app.text.text
import ro.safetyplease.app.text.quantityText
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
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
        { res.text(Res.string.place_near, it) }, { res.text(Res.string.place_accuracy, it) },
    )
}
