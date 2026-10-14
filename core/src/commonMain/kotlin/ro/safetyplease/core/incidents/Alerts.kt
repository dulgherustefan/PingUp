package ro.safetyplease.core.incidents

import ro.safetyplease.core.data.StaffIncident

/**
 * One notification to post now. [silent]: no sound or vibration, while the console is muted. [repeat]: an insistent
 * alert ringing again, with nothing new to say.
 */
data class AlertPost(val incident: StaffIncident, val route: Route, val fullScreen: Boolean, val silent: Boolean, val repeat: Boolean = false)

/** What to post, which insistent alerts to take down, and when to look again (null: only on a change). */
class AlertPlan(val post: List<AlertPost>, val stop: List<String>, val nextAt: Long?)

/**
 * Turns the routes into notifications without wearing people out (roadmap 2.7, N9): an incident alerts when its
 * route gets stronger or it is newly assigned, never twice within [COOLDOWN_MS]; the insistent one rings again every
 * [REPEAT_MS] until someone has the incident, and takes the full screen only once per incident. While muted, alerts
 * are posted without sound and nothing repeats. Incidents this phone received before [startMs] are taken as already
 * alerted, so a restart doesn't ring them again, but an insistent one keeps repeating.
 */
class AlertPlanner(private val startMs: Long) {
    private class Seen(var level: AlertLevel, var reason: AlertReason?, var epoch: Int, var lastAt: Long?, var fullScreenDone: Boolean)

    private val seen = HashMap<String, Seen>()

    /** [own]: reports this phone wrote, which never alert it. [mutedUntil]: wall time; 0 when not muted. */
    fun plan(me: StaffProfile, incidents: List<StaffIncident>, own: Set<String>, nowMs: Long, mutedUntil: Long = 0): AlertPlan {
        val muted = nowMs < mutedUntil
        val post = ArrayList<AlertPost>()
        val stop = ArrayList<String>()
        val wakes = ArrayList<Long>()
        seen.keys.retainAll(incidents.mapTo(HashSet()) { it.incidentId })
        for (incident in incidents) {
            if (incident.incidentId in own) continue
            val route = Routing.route(me, incident, nowMs)
            Routing.nextChange(me, incident, nowMs)?.let(wakes::add)
            val s = seen.getOrPut(incident.incidentId) {
                if (incident.receivedAt < startMs) Seen(route.level, route.reason, incident.epoch, startMs, true)
                else Seen(AlertLevel.SILENT, null, incident.epoch, null, false)
            }
            // a reopen is a new decision: it alerts again, but not on the full screen
            if (incident.epoch > s.epoch) {
                s.epoch = incident.epoch
                s.level = AlertLevel.SILENT
                s.reason = null
            }
            val news = route.level > s.level || (route.reason == AlertReason.ASSIGNED && s.reason != AlertReason.ASSIGNED)
            if (route.level < s.level && !news) {
                if (s.level == AlertLevel.INSISTENT) stop += incident.incidentId
                s.level = route.level
                s.reason = route.reason
            }
            if (route.level == AlertLevel.SILENT) continue
            val repeat = !news && route.level == AlertLevel.INSISTENT
            val wait = if (news) COOLDOWN_MS else REPEAT_MS
            val last = s.lastAt
            when {
                repeat && muted -> wakes += mutedUntil
                !news && !repeat -> Unit
                last != null && nowMs - last < wait -> wakes += last + wait
                else -> {
                    val fullScreen = route.level == AlertLevel.INSISTENT && !s.fullScreenDone && !muted
                    post += AlertPost(incident, route, fullScreen, muted, repeat)
                    s.level = route.level
                    s.reason = route.reason
                    s.lastAt = nowMs
                    if (fullScreen) s.fullScreenDone = true
                    if (route.level == AlertLevel.INSISTENT) wakes += nowMs + REPEAT_MS
                }
            }
        }
        return AlertPlan(post, stop, wakes.filter { it > nowMs }.minOrNull())
    }

    companion object {
        const val REPEAT_MS = 30_000L
        const val COOLDOWN_MS = 60_000L

        /** "Silențios 10 min" on the console. */
        const val MUTE_MS = 10 * 60_000L
    }
}
