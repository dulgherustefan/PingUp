package ro.safetyplease.core.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.event.MyProgram
import ro.safetyplease.core.event.Program
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.incidents.AlertLevel
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.incidents.escalationAt
import ro.safetyplease.core.position.SharePlan
import ro.safetyplease.core.position.ShareTime
import ro.safetyplease.core.safety.Checkin
import ro.safetyplease.core.util.Clock

/**
 * What the phone is to show later ([ScheduledAlert]), from what the session keeps. An alert whose time has passed stays
 * listed while it still holds, so it isn't taken off the screen; [ScheduleBook] hands over only what is still to come.
 */
object AlertSchedule {
    /** An insistent alert rings again this often while the app sleeps: iOS repeats nothing more often than once a minute. */
    const val REPEAT_EVERY_MS = 60_000L

    /** The sets reminded ahead, the soonest first: iOS keeps 64 pending notifications per app. */
    const val MAX_SETS = 12

    /** The time of a set reminder the app already rang: nothing left to schedule, but it stays until the set ends. */
    const val RUNG = 0L

    /** Each urgent report of yours still waiting for a person on staff, at the moment it escalates. */
    fun escalations(mine: List<MyReport>): List<ScheduledAlert.Escalation> = mine.mapNotNull { report ->
        escalationAt(report)?.let { ScheduledAlert.Escalation(report.incidentId, report.status, report.teamName, Discreet.isDiscreet(report.description), it) }
    }

    /** The question at the deadline, for as long as the check-in runs: once asked, or with the friends told, it stays. */
    fun checkin(current: Checkin?): List<ScheduledAlert.CheckinDue> = listOfNotNull(current?.let { ScheduledAlert.CheckinDue(it.label, it.deadlineAt) })

    /**
     * The sets with "Amintește-mi" on, until they end: the soonest [MAX_SETS] that haven't started, at the time
     * [Program.remindAt] gives with the walk to the stage from where the phone is now ([walkMeters]), and those already
     * rung or started, at [RUNG]. [stage] names a set's stage.
     */
    fun sets(
        program: MyProgram,
        schedule: List<ScheduleItem>,
        nowSec: Long,
        walkMeters: (ScheduleItem) -> Int?,
        stage: (ScheduleItem) -> String,
    ): List<ScheduledAlert.SetReminder> {
        val on = schedule.filter { Program.key(it) in program.reminders && nowSec < it.end }
        val (rung, coming) = on.partition { Program.key(it) in program.fired || it.start <= nowSec }
        return rung.map { ScheduledAlert.SetReminder(it, stage(it), 0, RUNG) } + coming.sortedBy { it.start }.take(MAX_SETS).map { item ->
            val walk = walkMeters(item)
            ScheduledAlert.SetReminder(item, stage(item), Program.walkMinutes(walk), Program.remindAt(item, program.leadMin, walk) * 1000)
        }
    }

    /** The end of this phone's shift ([validUntilSec], null off shift), once the chain no longer takes it ([KeyChain.SKEW_S]). */
    fun shiftEnd(validUntilSec: Long?): List<ScheduledAlert.ShiftEnd> =
        listOfNotNull(validUntilSec?.let { ScheduledAlert.ShiftEnd((it + KeyChain.SKEW_S + 1) * 1000) })

    /** The next reminder of the shares without an end ([ShareTime.reminderDue]), while [people] see this phone. */
    fun shareReminder(plans: List<SharePlan>, people: Int): List<ScheduledAlert.ShareReminder> {
        if (people <= 0) return emptyList()
        val next = plans.filter { it.endAtMs == null }.minOfOrNull { maxOf(it.startedAtMs, it.remindedAtMs) + ShareTime.REMINDER_MS } ?: return emptyList()
        return listOf(ScheduledAlert.ShareReminder(people, next))
    }

    /**
     * What rings again after [post] went out: an insistent, unmuted alert, every [REPEAT_EVERY_MS]; nothing for any
     * other. A plain repeat keeps the one [held] for its incident, so the cadence runs on; news starts it again.
     */
    fun repeat(post: AlertPost, held: ScheduledAlert.AlertRepeat?, nowMs: Long): ScheduledAlert.AlertRepeat? = when {
        post.route.level != AlertLevel.INSISTENT || post.silent -> null
        post.repeat && held != null -> held
        else -> ScheduledAlert.AlertRepeat(post.copy(fullScreen = false, repeat = true), REPEAT_EVERY_MS, nowMs + REPEAT_EVERY_MS)
    }
}

/**
 * What the phone holds for later, by group: each sync names everything a group wants now, and the book tells what to
 * hand the phone (new, or changed, and still to come) and what to take back (no longer wanted). An alert taken back
 * goes off the screen too, except a shift's end that has shown: it says why the phone left its shift.
 */
class ScheduleBook {
    class Changes(val schedule: List<ScheduledAlert>, val cancel: List<String>)

    private class Held(val group: String, val alert: ScheduledAlert)

    private val held = LinkedHashMap<String, Held>()

    fun sync(group: String, wanted: List<ScheduledAlert>, nowMs: Long): Changes {
        val ids = wanted.mapTo(HashSet()) { it.id }
        val cancel = ArrayList<String>()
        for ((id, gone) in held.filter { (id, h) -> h.group == group && id !in ids }) {
            held.remove(id)
            if (gone.alert !is ScheduledAlert.ShiftEnd || gone.alert.atMs > nowMs) cancel += id
        }
        val schedule = ArrayList<ScheduledAlert>()
        for (alert in wanted) {
            val before = held[alert.id]?.alert
            held[alert.id] = Held(group, alert)
            if (alert != before && alert.atMs > nowMs) schedule += alert
        }
        return Changes(schedule, cancel)
    }

    fun held(id: String): ScheduledAlert? = held[id]?.alert

    /** The groups that hold something, in the order they first did. */
    fun groups(): List<String> = held.values.map { it.group }.distinct()
}

/**
 * Keeps what the phone has scheduled ([ScheduledAlert]) in step with the session: each call says what a part of the
 * session holds now, from any thread, and the phone gets the changes in the order they were decided.
 */
class AlertScheduler(private val notifications: Notifications, private val clock: Clock) {
    private val lock = SynchronizedObject()
    private val book = ScheduleBook()

    fun escalations(mine: List<MyReport>) = sync(ESCALATIONS, AlertSchedule.escalations(mine))

    fun checkin(current: Checkin?) = sync(CHECKIN, AlertSchedule.checkin(current))

    fun sets(reminders: List<ScheduledAlert.SetReminder>) = sync(SETS, reminders)

    /** [validUntilSec]: the shift certificate's end; null off shift. */
    fun shiftEnd(validUntilSec: Long?) = sync(SHIFT, AlertSchedule.shiftEnd(validUntilSec))

    fun share(plans: List<SharePlan>, people: Int) = sync(SHARE, AlertSchedule.shareReminder(plans, people))

    /** After [post] went out: an insistent alert rings again until it's stopped; any other alert stops its incident's. */
    fun repeated(post: AlertPost) = synchronized(lock) {
        val id = post.incident.incidentId
        val held = book.held(ScheduledAlert.repeat(id)) as? ScheduledAlert.AlertRepeat
        apply(REPEAT + id, listOfNotNull(AlertSchedule.repeat(post, held, clock.wallMs())))
    }

    fun stopRepeat(incidentId: String) = sync(REPEAT + incidentId, emptyList())

    /** Only the alerts of [incidentIds] go on ringing again; with none, nothing does (muted, or off shift). */
    fun keepRepeats(incidentIds: Set<String>) = synchronized(lock) {
        book.groups().filter { it.startsWith(REPEAT) && it.removePrefix(REPEAT) !in incidentIds }.forEach { apply(it, emptyList()) }
    }

    private fun sync(group: String, wanted: List<ScheduledAlert>) = synchronized(lock) { apply(group, wanted) }

    private fun apply(group: String, wanted: List<ScheduledAlert>) {
        val changes = book.sync(group, wanted, clock.wallMs())
        changes.cancel.forEach(notifications::cancelScheduled)
        changes.schedule.forEach(notifications::scheduleAlert)
    }

    private companion object {
        const val ESCALATIONS = "escalations"
        const val CHECKIN = "checkin"
        const val SETS = "sets"
        const val SHIFT = "shift"
        const val SHARE = "share"
        const val REPEAT = "repeat:"
    }
}
