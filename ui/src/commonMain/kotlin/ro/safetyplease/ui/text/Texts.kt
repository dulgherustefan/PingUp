package ro.safetyplease.ui.text

import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getStringArray
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.day_names
import ro.safetyplease.ui.resources.day_short_names
import ro.safetyplease.ui.resources.month_names
import ro.safetyplease.ui.resources.month_short_names

/**
 * The texts [label] gives [codes], read now for code that names them without waiting, as exports do. Any code reads
 * as the text [label] gives it, an unknown one included.
 */
suspend fun textsOf(
    codes: Iterable<Int>,
    label: (Int) -> StringResource,
    read: suspend (StringResource) -> String = { getString(it) },
): (Int) -> String {
    val texts = (codes.map(label) + label(Int.MIN_VALUE)).distinct().associateWith { read(it) }
    return { texts[label(it)].orEmpty() }
}

/** The names [rememberDateNames] gives, outside composition. */
suspend fun dateNames(): DateNames = DateNames(
    getStringArray(Res.array.month_names), getStringArray(Res.array.month_short_names),
    getStringArray(Res.array.day_names), getStringArray(Res.array.day_short_names),
)
