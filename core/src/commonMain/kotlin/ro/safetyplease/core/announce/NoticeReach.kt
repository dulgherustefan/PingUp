package ro.safetyplease.core.announce

import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.Venue

/** How a notice reaches the person: as an ordinary notice, taking the screen without an alarm (an exercise), or with one. */
enum class NoticeAlarm { NONE, SILENT, LOUD }

/**
 * Whom a notice alarms. A pause or an evacuation takes the screen and sounds only on the phones it is for: those in its
 * zone set or its zone, or those that don't know where they are. The others show it in the list, quietly.
 */
object NoticeReach {
    /**
     * The zone this phone is in: from the simulated position or a fresh fix, else the zone picked by hand. "" outside
     * every zone, null when the phone knows neither.
     */
    fun zone(venue: Venue, settings: Settings, fix: GeoFix?, nowMs: Long): String? {
        val simLat = settings.simLat
        val simLon = settings.simLon
        val point = when {
            simLat != null && simLon != null -> simLat to simLon
            fix != null && fix.isFresh(nowMs) -> fix.lat to fix.lon
            else -> null
        }
        val here = point?.let { (lat, lon) -> venue.zoneAt(lat, lon)?.id }
        return here ?: settings.manualZone.ifEmpty { null } ?: point?.let { "" }
    }

    /** A phone that doesn't know its zone ([zone] null) may be in any: it counts as concerned. */
    fun concerns(notice: Notice, venue: Venue, zone: String?): Boolean = zone == null || notice.concerns(venue, zone)

    fun alarm(notice: Notice, venue: Venue, zone: String?, exercise: Boolean): NoticeAlarm = when {
        !notice.urgent || !concerns(notice, venue, zone) -> NoticeAlarm.NONE
        exercise -> NoticeAlarm.SILENT
        else -> NoticeAlarm.LOUD
    }
}

/** The zone set of a notice for [zones] of [venue]; null for none or for every zone, the whole site, so older phones lose nothing. */
fun ZoneSet.Companion.target(venue: Venue, zones: Set<String>): ZoneSet? {
    if (venue.zones.size > ZoneSet.MAX_ZONES) return null
    return ZoneSet.of(venue, zones).takeIf { it.indices.isNotEmpty() && it.indices.size < venue.zones.size }
}
