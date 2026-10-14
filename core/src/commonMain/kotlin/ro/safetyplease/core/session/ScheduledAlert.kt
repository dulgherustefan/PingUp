package ro.safetyplease.core.session

import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.incidents.AlertPost

/**
 * A notification the phone has to show at [atMs] (wall clock) even if the app doesn't run then: an iPhone suspends the
 * app between Bluetooth events, and any phone may end it. The session hands it over ahead of time
 * ([Notifications.scheduleAlert]) and takes it back once it no longer holds ([Notifications.cancelScheduled]); its own
 * timers still run while the app does. [id] names the subject: scheduling it again replaces it, and the app's own
 * notification for the same subject carries the same id, so the person sees it once. Each kind holds what its words
 * need, so the phone can word it with the app not running.
 */
sealed class ScheduledAlert {
    abstract val id: String
    abstract val atMs: Long

    /** An urgent report of yours nobody on staff confirmed in time: look for help in person, with "Sună la 112". */
    data class Escalation(
        val incidentId: String,
        val status: Int,
        val teamName: String,
        /** Sent discreetly: it is answered as quietly. */
        val discreet: Boolean,
        override val atMs: Long,
    ) : ScheduledAlert() {
        override val id: String get() = escalation(incidentId)
    }

    /** "Ai ajuns?" at the check-in's deadline, answered from the notification: Am ajuns, +15 or +30 min. */
    data class CheckinDue(val label: String, override val atMs: Long) : ScheduledAlert() {
        override val id: String get() = CHECKIN
    }

    /** "Amintește-mi" for [set] on [stage] (its name), [walkMin] minutes of walking away when it was planned. */
    data class SetReminder(val set: ScheduleItem, val stage: String, val walkMin: Int, override val atMs: Long) : ScheduledAlert() {
        override val id: String get() = setReminder(set)
    }

    /** The shift certificate runs out: the phone is a participant's again. */
    data class ShiftEnd(override val atMs: Long) : ScheduledAlert() {
        override val id: String get() = SHIFT_ENDED
    }

    /** Hours into a location share without an end: [people] still see this phone. */
    data class ShareReminder(val people: Int, override val atMs: Long) : ScheduledAlert() {
        override val id: String get() = SHARING
    }

    /** An insistent staff alert rings again at [atMs] and every [everyMs] after, until it is taken back. */
    data class AlertRepeat(val post: AlertPost, val everyMs: Long, override val atMs: Long) : ScheduledAlert() {
        override val id: String get() = repeat(post.incident.incidentId)
    }

    /** The ids, the same for what is scheduled and for what the running app posts. */
    companion object {
        const val CHECKIN = "checkin"
        const val SHIFT_ENDED = "shift-ended"
        const val SHARING = "sharing"

        fun escalation(incidentId: String) = "escalate-$incidentId"

        fun setReminder(set: ScheduleItem) = "set-${set.stage}@${set.start}"

        fun alert(incidentId: String) = "alert-$incidentId"

        fun repeat(incidentId: String) = "${alert(incidentId)}-again"
    }
}
