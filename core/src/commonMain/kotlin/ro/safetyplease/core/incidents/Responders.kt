package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.distanceMeters

/**
 * Where a responder goes: the [patient], and the defibrillator that adds the least walking on the way there ([dea],
 * null when the venue lists none that can be walked to). Distances are walking metres, null when unknown. The walking
 * routes draw it.
 */
data class ResponderTarget(
    val incidentId: String,
    val patient: GeoPoint,
    /** From the responder straight to the patient. */
    val direct: Int?,
    val dea: Poi?,
    val toDea: Int?,
    val deaToPatient: Int?,
)

/**
 * "Inconștient / nu respiră" (roadmap row 17, staff only): while nobody has the incident, the medical role and the
 * first-aid responders within [RANGE_M] hear it insistently at once, with the defibrillator to fetch on the way. Who
 * counts as a responder goes by the team on the shift certificate, as roles do in [Routing].
 */
object Responders {
    const val RANGE_M = 300

    /** Teams sent to an arrest besides the medical role; the medical lead fixes the list. */
    val ROLES = listOf("Medical", "Prim ajutor", "First aid", "Responder")

    fun applies(incident: StaffIncident): Boolean = incident.unconscious || incident.breathes == Answer.NO

    fun isResponder(team: String): Boolean = team.isNotBlank() && ROLES.any { Routing.inRole(it, team) }

    /** By the two positions; without either of them, by the zone. */
    fun inRange(me: StaffProfile, incident: StaffIncident): Boolean {
        val here = me.position
        val patient = patientPoint(incident)
        if (here != null && patient != null) return distanceMeters(here, patient) <= RANGE_M
        return me.zone.isNotBlank() && incident.zone.trim().equals(me.zone.trim(), ignoreCase = true)
    }

    fun alerts(me: StaffProfile, incident: StaffIncident): Boolean =
        !me.console && applies(incident) && isResponder(me.team) && inRange(me, incident)

    fun patientPoint(incident: StaffIncident): GeoPoint? {
        val lat = incident.lat
        val lon = incident.lon
        return if (lat != null && lon != null) GeoPoint(lat, lon) else null
    }

    /** The patient's place (the reported position, else the middle of its zone) and the best defibrillator from [from]. */
    fun target(incident: StaffIncident, from: GeoPoint?, venue: Venue, walk: WalkDistance): ResponderTarget? {
        val patient = patientPoint(incident) ?: venue.zone(incident.zone)?.center ?: return null
        val best = venue.pois.filter { it.type == Venue.DEA }.mapNotNull { dea ->
            val back = walk.meters(dea.point, patient) ?: return@mapNotNull null
            val there = if (from == null) 0 else walk.meters(from, dea.point) ?: return@mapNotNull null
            Triple(dea, there, back)
        }.minByOrNull { (_, there, back) -> there + back }
        return ResponderTarget(
            incident.incidentId, patient, from?.let { walk.meters(it, patient) },
            best?.first, best?.second?.takeIf { from != null }, best?.third,
        )
    }
}
