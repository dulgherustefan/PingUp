package ro.safetyplease.core.announce

import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.metersToPolygon

/** How a notice reaches the person: as an ordinary notice, taking the screen without an alarm (an exercise), or with one. */
enum class NoticeAlarm { NONE, SILENT, LOUD }

/**
 * Where the phone is, for the notices: [zone] as [NoticeReach.zone] reads it, and [near], the zones its fix's accuracy
 * circle reaches, since a phone at the edge of a zone may be on either side of it.
 */
data class Whereabouts(val zone: String?, val near: Set<String> = emptySet())

/**
 * Whom a notice alarms. A pause or an evacuation takes the screen and sounds only on the phones it is for: those in its
 * zone set or its zone, or near enough to be in it, or those that don't know where they are. The others show it in the
 * list, quietly.
 */
object NoticeReach {
    /** A zone picked by hand longer ago than this may no longer be where the person is: it doesn't count. */
    const val MANUAL_ZONE_MS = 20 * 60_000L

    /**
     * The zone this phone is in: from the simulated position or a fresh fix, else the zone picked by hand in the last
     * [MANUAL_ZONE_MS]. "" outside every zone, null when the phone knows neither.
     */
    fun zone(venue: Venue, settings: Settings, fix: GeoFix?, nowMs: Long): String? {
        val point = point(settings, fix, nowMs)
        val here = point?.let { venue.zoneAt(it.lat, it.lon)?.id }
        val manual = settings.manualZone.takeIf { it.isNotEmpty() && settings.manualZoneAt > 0 && nowMs - settings.manualZoneAt in 0..MANUAL_ZONE_MS }
        return here ?: manual ?: point?.let { "" }
    }

    /** The zone and the zones within the accuracy of a fresh fix; the simulated position is exact. */
    fun whereabouts(venue: Venue, settings: Settings, fix: GeoFix?, nowMs: Long): Whereabouts {
        val zone = zone(venue, settings, fix, nowMs)
        val simulated = settings.simLat != null && settings.simLon != null
        if (simulated || fix == null || !fix.isFresh(nowMs) || fix.accuracyM <= 0f) return Whereabouts(zone)
        val at = GeoPoint(fix.lat, fix.lon)
        val near = venue.zones.filter { it.id != zone && metersToPolygon(at, it.polygon) <= fix.accuracyM }.map { it.id }.toSet()
        return Whereabouts(zone, near)
    }

    private fun point(settings: Settings, fix: GeoFix?, nowMs: Long): GeoPoint? {
        val simLat = settings.simLat
        val simLon = settings.simLon
        return when {
            simLat != null && simLon != null -> GeoPoint(simLat, simLon)
            fix != null && fix.isFresh(nowMs) -> GeoPoint(fix.lat, fix.lon)
            else -> null
        }
    }

    /** A phone that doesn't know its zone may be in any: it counts as concerned, as one whose fix reaches a zone of it. */
    fun concernsAt(notice: Notice, venue: Venue, here: Whereabouts): Boolean =
        here.zone == null || notice.concerns(venue, here.zone) || here.near.any { notice.concerns(venue, it) }

    fun concerns(notice: Notice, venue: Venue, zone: String?): Boolean = concernsAt(notice, venue, Whereabouts(zone))

    fun alarmAt(notice: Notice, venue: Venue, here: Whereabouts, exercise: Boolean): NoticeAlarm = when {
        !notice.urgent || !concernsAt(notice, venue, here) -> NoticeAlarm.NONE
        exercise -> NoticeAlarm.SILENT
        else -> NoticeAlarm.LOUD
    }

    fun alarm(notice: Notice, venue: Venue, zone: String?, exercise: Boolean): NoticeAlarm = alarmAt(notice, venue, Whereabouts(zone), exercise)

    fun isEvacuation(notice: Notice): Boolean = notice.topic == NoticeTopic.ALERT && notice.level == NoticeLevel.EVACUATION

    /**
     * Whether this phone posts [notice], which takes [alarm] here. A phone that [showsNotices] posts every notice with
     * words; one that [showsEvacuation] posts, besides, an evacuation for its zone and its withdrawal, whatever the
     * person chose for the other notices, as a cell broadcast would.
     */
    fun posts(notice: Notice, alarm: NoticeAlarm, showsNotices: Boolean, showsEvacuation: Boolean): Boolean = when {
        notice.silent -> false
        showsNotices -> true
        else -> showsEvacuation && isEvacuation(notice) && (notice.cancelled || alarm != NoticeAlarm.NONE)
    }
}

/** The zone set of a notice for [zones] of [venue]; null for none or for every zone, the whole site, so older phones lose nothing. */
fun ZoneSet.Companion.target(venue: Venue, zones: Set<String>): ZoneSet? {
    if (venue.zones.size > ZoneSet.MAX_ZONES) return null
    return ZoneSet.of(venue, zones).takeIf { it.indices.isNotEmpty() && it.indices.size < venue.zones.size }
}
