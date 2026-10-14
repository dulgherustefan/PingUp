package ro.safetyplease.app.demo

import android.content.Context
import kotlinx.coroutines.runBlocking
import ro.safetyplease.app.AppContainer
import ro.safetyplease.core.announce.Evacuations
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.control.DensityReading
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Group
import ro.safetyplease.core.data.GroupMember
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Inner
import ro.safetyplease.core.protocol.LastFix
import ro.safetyplease.core.protocol.LostAction
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue

/**
 * The "vitrina" command: on one phone, what the new screens need to be seen, under the demo exercise so no live alarm
 * ever sounds. The console's part (a map state with a closed and a crowded zone, an evacuation of one zone, marked
 * EXERCIȚIU) is signed with an exercise control shift; then the phone becomes a medical team in that zone, with demo
 * friends around it, a "M-am pierdut" and a missed check-in from them, crowd levels and an unconscious report for the
 * responder alert. [clear] takes back what it can; the exercise's own files stay with the exercise.
 */
object DemoShowcase {
    const val TEAM = "Medical 1"
    const val GROUP_ID = DemoFriends.ID_BASE + 0x100
    const val INCIDENT_ID_TAIL = "5ca1ab1e"
    private const val MIN = 60_000L
    private const val VALID_S = 4 * 3600L
    private const val LOST_MSG = 0x0dec0de5L
    private const val CHECKIN_MSG = 0x0dec0de6L

    /** The zones it uses: the evacuated one (where the phone stands), the one closed and the one crowded. */
    class Zones(val evacuated: String, val closed: String, val crowded: String)

    /**
     * In the bundled venue: the second stage is evacuated, the east gate (its main exit) closed so the phone is sent
     * to its alternative, the bars crowded; elsewhere, the first zones of the venue.
     */
    fun zones(venue: Venue): Zones? {
        fun has(id: String) = venue.zone(id) != null
        if (has("second-stage") && has("gate-east") && has("bar")) return Zones("second-stage", "gate-east", "bar")
        val ids = venue.zones.map { it.id }
        return if (ids.size >= 3) Zones(ids[0], ids[1], ids[2]) else null
    }

    /** The map state, one per zone in the venue's order. */
    fun states(venue: Venue, zones: Zones): List<ZoneState> = venue.zones.map {
        when (it.id) {
            zones.closed -> ZoneState.CLOSED
            zones.crowded -> ZoneState.CROWDED
            else -> ZoneState.OPEN
        }
    }

    val incidentId: String get() = DemoScenario.ID_PREFIX + INCIDENT_ID_TAIL.padStart(32 - DemoScenario.ID_PREFIX.length, '0')

    /** Someone who doesn't respond or breathe 40 m from where the phone stands, which nobody has taken yet. */
    fun unconscious(venue: Venue, at: GeoPoint, zone: String, nowMs: Long): StaffIncident {
        val patient = DemoFriends.offset(at, 25.0, 30.0)
        return StaffIncident(
            incidentId = incidentId,
            category = IncidentCategory.MEDICAL,
            severity = Severity.URGENT,
            zone = zone,
            lat = patient.lat,
            lon = patient.lon,
            description = Recognition.join("tricou verde", "${DemoScenario.MARK}: nu răspunde, nu respiră"),
            reportedAt = nowMs - MIN,
            receivedAt = nowMs - MIN + 2_000,
            hops = 2,
            status = AckStatus.RECEIVED,
            responds = Answer.NO,
            breathes = Answer.NO,
            unconscious = true,
            accuracyM = 8,
            landmark = venue.nearestLandmark(patient)?.number ?: 0,
        )
    }

    /** Three zones' crowd levels, the crowded one at 4, as staff phones keep them. */
    fun density(zones: Zones, nowMs: Long): List<DensityReading> = listOf(
        DensityReading(zones.crowded, 4, nowMs - 4 * MIN, nowMs - 4 * MIN, "Stewarzi 1"),
        DensityReading(zones.evacuated, 3, nowMs - 9 * MIN, nowMs - 9 * MIN, "Stewarzi 2"),
        DensityReading(zones.closed, 2, nowMs - 15 * MIN, nowMs - 15 * MIN, "Securitate 1"),
    )

    /** The demo friends' group, where the second friend says they are lost. */
    fun group(c: AppContainer, nowMs: Long): Group = Group(
        GROUP_ID, "Gașca (demo)",
        c.friends.value.filter { DemoFriends.isDemo(it.nodeId) }.map { GroupMember(it.nodeId, it.nickname, it.boxKey) }, nowMs,
    )

    /**
     * Seeds it all on a phone already in the demo exercise; [evacuate] false leaves the evacuation out, or withdraws
     * one seeded before, so the friends' dialogs, which wait while it holds, can be seen.
     */
    fun seed(context: Context, c: AppContainer, evacuate: Boolean): String {
        val zones = zones(c.venue) ?: return "the venue has fewer than three zones"
        val now = c.clock.wallMs()
        if (c.settings.value.role != Role.CONTROL && !Demo.becomeControl(context, c)) {
            return "the demo exercise refused the console's shift"
        }
        runBlocking { c.notices.publishMapState(states(c.venue, zones), VALID_S) } ?: return "the map state was not signed"
        if (evacuate) {
            val template = c.noticeTemplates.forTopic(NoticeTopic.ALERT).filter { it.level == NoticeLevel.EVACUATION }.minByOrNull { it.params.size }
                ?: return "no evacuation template"
            val extras = Evacuations.extras(c.venue, c.venue.evacuation.firstOrNull()?.id, setOf(zones.evacuated)) ?: return "no usable plan"
            runBlocking { c.notices.publish(template, template.params.map { "EXERCIȚIU" }, zones.evacuated, VALID_S, extras) }
                ?: return "the evacuation was not signed"
        } else {
            runBlocking { c.notices.cancel(NoticeTopic.ALERT) }
        }
        if (!Demo.becomeStaff(context, c, TEAM, zones.evacuated, lead = true)) return "the demo exercise refused the team's shift"

        val here = c.venue.zone(zones.evacuated)!!.center
        c.settings.update { it.copy(simLat = here.lat, simLon = here.lon) }
        DemoFriends.seed(c, fresh = true)
        val group = group(c, now)
        c.groups.update { list -> list.filterNot { it.id == GROUP_ID } + group }
        val friends = group.members.map { it.nodeId }
        val meeting = c.venue.meetingPlaces().firstOrNull()?.number ?: 0
        friends.getOrNull(1)?.let { lost ->
            c.regasire.heard(lost, Conversations.group(GROUP_ID), Inner.LostSession(LOST_MSG, GROUP_ID, LostAction.START, 1, 30, 35, meeting))
        }
        friends.getOrNull(2)?.let { late ->
            val last = DemoFriends.offset(here, -120.0, -90.0)
            val fix = LastFix(last.lat, last.lon, (now - 12 * MIN) / 1000, AccuracyClass.of(15.0))
            c.checkin.onFriendMissed(late, Inner.CheckinMissed(CHECKIN_MSG, null, (now - 10 * MIN) / 1000, fix, "Acasă"))
        }
        density(zones, now).forEach(c.staffChain.density::offer)
        val incident = unconscious(c.venue, here, zones.evacuated, now)
        c.incidentStore.update { data -> data.copy(staff = data.staff.filterNot { it.incidentId == incident.incidentId } + incident) }
        val evacuation = if (evacuate) "an exercise evacuation of ${zones.evacuated}" else "no evacuation"
        return "$evacuation, ${zones.closed} closed, ${zones.crowded} crowded; team $TEAM in ${zones.evacuated} with friends, " +
            "a lost alert, a missed check-in, crowd levels and an unconscious report"
    }

    /** Takes back what [seed] put on the phone: the console's notices, friends, group, alerts and simulated position. */
    fun clear(context: Context, c: AppContainer) {
        if (c.exercise && (c.settings.value.role == Role.CONTROL || Demo.becomeControl(context, c))) {
            runBlocking {
                c.notices.cancel(NoticeTopic.ALERT)
                c.notices.cancel(NoticeTopic.MAP_STATE)
            }
        }
        c.incidentStore.update { data -> data.copy(staff = data.staff.filterNot { it.incidentId == incidentId }) }
        c.regasire.alerts.value.filter { DemoFriends.isDemo(it.sender) }.forEach(c.regasire::dismiss)
        c.checkin.state.value.alerts.filter { DemoFriends.isDemo(it.sender) }.forEach { c.checkin.dismissAlert(it.sender, it.msgId) }
        c.groups.update { list -> list.filterNot { it.id == GROUP_ID } }
        DemoFriends.remove(c)
        c.settings.update { it.copy(simLat = null, simLon = null) }
        c.leaveStaff()
    }
}
