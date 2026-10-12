package ro.safetyplease.core.venue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The venue with zone and place names in [language], from the optional "name_<language>" keys of venue.json
 * (e.g. "name_en"). Entries without one keep "name", which is Romanian. Ids, shapes and order don't change.
 */
fun Venue.Companion.parse(text: String, language: String): Venue {
    val venue = parse(text)
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return venue
    val zones = root.entries("zones")
    val pois = root.entries("pois")
    return venue.copy(
        zones = venue.zones.mapIndexed { i, zone -> zone.copy(name = displayName(zones.getOrNull(i), zone.name, language)) },
        pois = venue.pois.mapIndexed { i, poi -> poi.copy(name = displayName(pois.getOrNull(i), poi.name, language)) },
    )
}

private fun displayName(entry: JsonObject?, default: String, language: String): String =
    (entry?.get("name_$language") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: default

private fun JsonObject.entries(key: String): List<JsonObject?> = (get(key) as? JsonArray)?.map { it as? JsonObject } ?: emptyList()
