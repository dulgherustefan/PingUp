package ro.safetyplease.core.session

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.crypto.KeyChain
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.event.MyProgram
import ro.safetyplease.core.event.Program
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.incidents.AlertLevel
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.incidents.AlertReason
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.incidents.ESCALATE_AFTER_MS
import ro.safetyplease.core.incidents.Route
import ro.safetyplease.core.position.SharePlan
import ro.safetyplease.core.position.ShareTime
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.safety.Checkin
import ro.safetyplease.core.safety.CheckinPhase
import ro.safetyplease.core.venue.Venue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertScheduleTest {
    private val now = 1_760_000_000_000L
    private val clock = ManualClock(now)
    private val phone = Scheduled()
    private val scheduler = AlertScheduler(phone, clock)

    /** Remembers what the session scheduled with the phone and took back, in order, from any thread. */
    private class Scheduled : Notifications by FakeNotifications() {
        private val lock = SynchronizedObject()
        private val calls = mutableListOf<String>()
        val alerts = mutableListOf<ScheduledAlert>()

        fun calls(): List<String> = synchronized(lock) { calls.toList() }

        fun take(): List<String> = synchronized(lock) { calls.toList().also { calls.clear() } }

        override fun scheduleAlert(alert: ScheduledAlert) = synchronized(lock) {
            calls += "at ${alert.atMs} ${alert.id}"
            alerts += alert
            Unit
        }

        override fun cancelScheduled(id: String) = synchronized(lock) {
            calls += "cancel $id"
            Unit
        }
    }

    private fun report(status: Int = AckStatus.NONE, severity: Int = Severity.URGENT, description: String = "", createdAt: Long = now) = MyReport(
        incidentId = "r1", category = IncidentCategory.MEDICAL, severity = severity, zone = "camping", description = description,
        createdAt = createdAt, status = status,
    )

    private fun incident(id: String = "i1") = StaffIncident(
        incidentId = id, category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = "bar", reportedAt = now, receivedAt = now, hops = 1,
    )

    private fun insistent(id: String = "i1", reason: AlertReason = AlertReason.CONSOLE, repeat: Boolean = false, silent: Boolean = false) =
        AlertPost(incident(id), Route(AlertLevel.INSISTENT, reason), fullScreen = !repeat, silent = silent, repeat = repeat)

    @Test
    fun anUrgentReportEscalatesAtItsMomentAndIsTakenBackOnceAPersonHasIt() {
        val at = now + ESCALATE_AFTER_MS
        scheduler.escalations(listOf(report()))
        assertEquals(listOf("at $at escalate-r1"), phone.take())
        scheduler.escalations(listOf(report()))
        assertEquals(emptyList(), phone.take(), "nothing changed")

        scheduler.escalations(listOf(report(AckStatus.RECEIVED)))
        assertEquals(listOf("at $at escalate-r1"), phone.take(), "the words name the new status")
        assertEquals(AckStatus.RECEIVED, (phone.alerts.last() as ScheduledAlert.Escalation).status)

        scheduler.escalations(listOf(report(AckStatus.ACKNOWLEDGED)))
        assertEquals(listOf("cancel escalate-r1"), phone.take())
    }

    @Test
    fun anEscalationThatShowedStaysUntilThereIsNoLongerAReasonForIt() {
        scheduler.escalations(listOf(report()))
        phone.take()
        clock.wall = now + ESCALATE_AFTER_MS + 1_000
        scheduler.escalations(listOf(report(AckStatus.RECEIVED)))
        assertEquals(emptyList(), phone.take(), "the phone showed it at its time; nothing to hand over again")
        scheduler.escalations(listOf(report(AckStatus.RECEIVED).copy(cancelled = true)))
        assertEquals(listOf("cancel escalate-r1"), phone.take(), "a cancelled report takes it off the screen")
    }

    @Test
    fun onlyUrgentReportsEscalateAndADiscreetOneSaysSo() {
        assertEquals(emptyList(), AlertSchedule.escalations(listOf(report(severity = Severity.MEDIUM))))
        assertEquals(emptyList(), AlertSchedule.escalations(listOf(report(AckStatus.ACKNOWLEDGED))))
        val discreet = AlertSchedule.escalations(listOf(report(description = Discreet.MARK + "x"))).single()
        assertTrue(discreet.discreet)
        assertEquals(ScheduledAlert.escalation("r1"), discreet.id)
    }

    @Test
    fun theCheckinQuestionFollowsTheDeadlineUntilTheCheckinEnds() {
        val due = now + 30 * 60_000L
        val waiting = Checkin("Gară", now, due, listOf(1L))
        scheduler.checkin(waiting)
        assertEquals(listOf("at $due checkin"), phone.take())

        clock.wall = due + 1_000
        scheduler.checkin(waiting.copy(phase = CheckinPhase.ASKED, askedAt = due))
        assertEquals(emptyList(), phone.take(), "asked: the question stays on screen")

        val later = due + 15 * 60_000L
        scheduler.checkin(waiting.copy(deadlineAt = later))
        assertEquals(listOf("at $later checkin"), phone.take(), "+15 asks again at the new time")

        scheduler.checkin(null)
        assertEquals(listOf("cancel checkin"), phone.take(), "arrived: nothing more to ask")
    }

    @Test
    fun setRemindersRingAheadWithTheWalkAndStayUntilTheSetEnds() {
        val nowSec = now / 1000
        val soon = ScheduleItem("main-stage", "Trupa", nowSec + 3_600, nowSec + 7_200)
        val other = ScheduleItem("beach", "Alta", nowSec + 1_800, nowSec + 3_600)
        val off = ScheduleItem("beach", "Fără", nowSec + 900, nowSec + 1_200)
        val program = MyProgram(reminders = listOf(Program.key(soon), Program.key(other)), leadMin = 10)
        val sets = AlertSchedule.sets(program, listOf(soon, other, off), nowSec, { if (it == soon) 400 else null }) { it.stage.uppercase() }
        assertEquals(listOf(other, soon), sets.map { it.set }, "the soonest first, only those with the bell on")
        val walk = Program.walkMinutes(400)
        assertEquals(Program.remindAt(soon, 10, 400) * 1000, sets.last().atMs)
        assertEquals(walk, sets.last().walkMin)
        assertEquals("MAIN-STAGE", sets.last().stage)

        val rung = AlertSchedule.sets(program.copy(fired = listOf(Program.key(other))), listOf(soon, other), nowSec, { null }) { it.stage }
        assertEquals(AlertSchedule.RUNG, rung.first { it.set == other }.atMs, "rung by the app: kept, not scheduled again")
        assertEquals(emptyList(), AlertSchedule.sets(program, listOf(soon, other), soon.end, { null }) { it.stage }, "over: gone")

        val many = (1..20).map { ScheduleItem("s$it", "Set $it", nowSec + it * 600L, nowSec + it * 600L + 300) }
        val all = MyProgram(reminders = many.map(Program::key))
        assertEquals(AlertSchedule.MAX_SETS, AlertSchedule.sets(all, many, nowSec, { null }) { it.stage }.size, "iOS keeps 64 pending at most")
    }

    @Test
    fun aSetReminderTheAppRangIsNeitherScheduledAgainNorTakenOffTheScreen() {
        val nowSec = now / 1000
        val set = ScheduleItem("main-stage", "Trupa", nowSec + 3_600, nowSec + 7_200)
        val program = MyProgram(reminders = listOf(Program.key(set)))
        scheduler.sets(AlertSchedule.sets(program, listOf(set), nowSec, { null }) { it.stage })
        assertEquals(listOf("at ${Program.remindAt(set, program.leadMin, null) * 1000} ${ScheduledAlert.setReminder(set)}"), phone.take())
        scheduler.sets(AlertSchedule.sets(program.copy(fired = listOf(Program.key(set))), listOf(set), nowSec, { null }) { it.stage })
        assertEquals(emptyList(), phone.take())
        clock.wall = set.end * 1000
        scheduler.sets(AlertSchedule.sets(program, listOf(set), set.end, { null }) { it.stage })
        assertEquals(listOf("cancel ${ScheduledAlert.setReminder(set)}"), phone.take(), "the set is over: its reminder goes")
    }

    @Test
    fun theShiftsEndShowsAtItsTimeAndStaysToSayWhy() {
        val until = now / 1000 + 3_600
        val at = (until + KeyChain.SKEW_S + 1) * 1000
        scheduler.shiftEnd(until)
        assertEquals(listOf("at $at shift-ended"), phone.take())
        scheduler.shiftEnd(null)
        assertEquals(listOf("cancel shift-ended"), phone.take(), "left the shift before its end")

        scheduler.shiftEnd(until)
        phone.take()
        clock.wall = at + 1
        scheduler.shiftEnd(null)
        assertEquals(emptyList(), phone.take(), "it showed: it stays, the phone left its shift because of it")
    }

    @Test
    fun theShareReminderComesHoursIntoAShareWithoutAnEnd() {
        val open = SharePlan("f:1", null, now - 60_000, remindedAtMs = 0)
        assertEquals(now - 60_000 + ShareTime.REMINDER_MS, AlertSchedule.shareReminder(listOf(open), 2).single().atMs)
        assertEquals(emptyList(), AlertSchedule.shareReminder(listOf(open.copy(endAtMs = now + 60_000)), 2), "a share with an end stops by itself")
        assertEquals(emptyList(), AlertSchedule.shareReminder(listOf(open), 0), "nobody sees the phone")
        val reminded = open.copy(remindedAtMs = now)
        assertEquals(now + ShareTime.REMINDER_MS, AlertSchedule.shareReminder(listOf(reminded), 2).single().atMs)
    }

    @Test
    fun anInsistentAlertRingsAgainEveryMinuteUntilItIsStopped() {
        val at = now + AlertSchedule.REPEAT_EVERY_MS
        scheduler.repeated(insistent())
        assertEquals(listOf("at $at ${ScheduledAlert.repeat("i1")}"), phone.take())
        val again = phone.alerts.last() as ScheduledAlert.AlertRepeat
        assertTrue(again.post.repeat && !again.post.fullScreen, "it rings again with nothing new, never on the full screen")

        clock.wall = now + 30_000
        scheduler.repeated(insistent(repeat = true))
        assertEquals(emptyList(), phone.take(), "the app's own repeat leaves the cadence as it runs")
        scheduler.repeated(insistent(reason = AlertReason.ASSIGNED))
        assertEquals(listOf("at ${now + 30_000 + AlertSchedule.REPEAT_EVERY_MS} ${ScheduledAlert.repeat("i1")}"), phone.take(), "news starts it again")

        scheduler.repeated(insistent(silent = true))
        assertEquals(listOf("cancel ${ScheduledAlert.repeat("i1")}"), phone.take(), "muted: nothing rings again")
        scheduler.repeated(insistent(repeat = true))
        assertEquals(listOf("at ${now + 30_000 + AlertSchedule.REPEAT_EVERY_MS} ${ScheduledAlert.repeat("i1")}"), phone.take(), "after a restart, a repeat starts it")

        scheduler.repeated(insistent("i2"))
        phone.take()
        scheduler.keepRepeats(setOf("i2"))
        assertEquals(listOf("cancel ${ScheduledAlert.repeat("i1")}"), phone.take(), "an incident gone from the list stops")
        scheduler.stopRepeat("i2")
        assertEquals(listOf("cancel ${ScheduledAlert.repeat("i2")}"), phone.take(), "someone has it")
        scheduler.repeated(AlertPost(incident(), Route(AlertLevel.VIBRATE, AlertReason.ROLE), fullScreen = false, silent = false))
        assertEquals(emptyList(), phone.take(), "only the insistent level rings again")
    }

    private val tmp = TempDir()
    private val sessions = mutableListOf<PingUpSession>()

    @AfterTest
    fun cleanUp() {
        sessions.forEach { it.close() }
        tmp.delete()
    }

    /** The session as a phone runs it, with [phone] as its notifications. */
    private class SchedulingPlatform(val base: FakePlatform, private val shown: Notifications) : AppPlatform by base {
        override fun notifications(venue: Venue, exercise: Boolean): Notifications = shown
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(10)
    }

    @Test
    fun theSessionSchedulesAnUrgentReportsEscalationAndTheCheckinAndTakesThemBack() = runBlocking {
        val platform = SchedulingPlatform(FakePlatform(tmp), phone)
        val s = PingUpSession(platform).also { sessions += it }
        val created = platform.base.clock.wall
        s.incidentStore.update { it.copy(mine = listOf(report(createdAt = created))) }
        eventually { "at ${created + ESCALATE_AFTER_MS} escalate-r1" in phone.calls() }
        s.incidentStore.update { data -> data.copy(mine = data.mine.map { it.copy(status = AckStatus.ACKNOWLEDGED, teamName = "Medical 1") }) }
        eventually { "cancel escalate-r1" in phone.calls() }

        s.friends.update { listOf(Friend(7L, "Ana", "", "", 0)) }
        assertTrue(s.checkin.begin("Gară", 30, listOf(7L)))
        eventually { "at ${created + 30 * 60_000L} checkin" in phone.calls() }
        s.checkin.arrived()
        eventually { "cancel checkin" in phone.calls() }
    }
}
