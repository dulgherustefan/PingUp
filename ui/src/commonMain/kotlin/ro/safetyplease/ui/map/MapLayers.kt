package ro.safetyplease.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ro.safetyplease.core.venue.Found

/** How the organiser's site plan lies over the map; a tap on its chip goes to the next. */
enum class PlanShade(val opacity: Float?) {
    FULL(PlanImage.DEFAULT_OPACITY),
    FAINT(0.4f),
    HIDDEN(null),
    ;

    fun next(): PlanShade = entries[(ordinal + 1) % entries.size]
}

/** What the card under the map shows: a service by its pin's key, a search result, or the saved place. */
sealed interface LayerPick {
    data class Service(val key: String) : LayerPick

    data class Result(val found: Found) : LayerPick

    data object Saved : LayerPick
}

/**
 * The map's layers, kept by the view model so they outlive the map screen: the service types switched on, what was
 * picked and how the site plan lies. "Du-mă acolo" goes through [ro.safetyplease.ui.AppViewModel.navigate].
 */
class MapLayers {
    var types by mutableStateOf(emptySet<String>())
    var picked by mutableStateOf<LayerPick?>(null)
    var plan by mutableStateOf(PlanShade.FULL)

    fun toggle(type: String) {
        types = if (type in types) types - type else types + type
        if (type !in types && (picked as? LayerPick.Service)?.key?.startsWith("svc:$type:") == true) picked = null
    }
}
