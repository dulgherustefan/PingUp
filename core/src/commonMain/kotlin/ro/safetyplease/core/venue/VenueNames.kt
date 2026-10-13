package ro.safetyplease.core.venue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The venue with its own name and those of zones, places, gates, blocked areas and evacuation plans in [language],
 * from the optional "name_<language>" keys of venue.json (e.g. "name_en"). Entries without one keep "name", which is
 * Romanian.
 * Ids, shapes and order don't change.
 */
fun Venue.Companion.parse(text: String, language: String): Venue {
    val venue = parse(text)
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return venue
    val zones = root.entries("zones")
    val pois = root.entries("pois")
    val gates = root.entries("gates")
    val blocked = root.entries("blocked")
    val plans = root.entries("evacuation")
    return venue.copy(
        name = displayName(root, venue.name, language),
        zones = venue.zones.mapIndexed { i, zone -> zone.copy(name = displayName(zones.getOrNull(i), zone.name, language)) },
        pois = venue.pois.mapIndexed { i, poi -> poi.copy(name = displayName(pois.getOrNull(i), poi.name, language)) },
        gates = venue.gates.mapIndexed { i, gate -> gate.copy(name = displayName(gates.getOrNull(i), gate.name, language)) },
        blocked = venue.blocked.mapIndexed { i, area -> area.copy(name = displayName(blocked.getOrNull(i), area.name, language)) },
        evacuation = venue.evacuation.mapIndexed { i, plan -> plan.copy(name = displayName(plans.getOrNull(i), plan.name, language)) },
    )
}

private fun displayName(entry: JsonObject?, default: String, language: String): String =
    (entry?.get("name_$language") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: default

private fun JsonObject.entries(key: String): List<JsonObject?> = (get(key) as? JsonArray)?.map { it as? JsonObject } ?: emptyList()
