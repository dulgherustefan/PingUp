package ro.safetyplease.core.event

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import ro.safetyplease.core.util.utf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramTest {
    // 2026-08-14 00:00 UTC
    private val friday = 1_786_665_600L
    private fun at(hour: Int, minute: Int = 0, day: Int = 0) = friday + day * 86_400L + hour * 3600L + minute * 60L

    private val late = ScheduleItem("main", "Headliner", at(22), at(23, 30))
    private val midnight = ScheduleItem("main", "Peste miezul nopții", at(23, 30), at(1, day = 1))
    private val small = ScheduleItem("main", "Ultimul set", at(1, 15, day = 1), at(2, 30, day = 1))
    private val other = ScheduleItem("second", "Scena 2", at(23), at(0, 30, day = 1))
    private val schedule = listOf(late, other, midnight, small)

    @Test
    fun nowAndNextCrossMidnight() {
        assertEquals(NowNext(midnight, small), Program.nowNext(schedule, "main", at(0, 15, day = 1)))
        assertEquals(NowNext(midnight, small), Program.nowNext(schedule, "main", at(23, 50)))
        assertEquals(NowNext(null, small), Program.nowNext(schedule, "main", at(1, 5, day = 1)))
        assertEquals(NowNext(late, midnight), Program.nowNext(schedule, "main", at(22, 10)))
        assertEquals(NowNext(other, null), Program.nowNext(schedule, "second", at(0, 10, day = 1)))
    }

    @Test
    fun aSetEndsWhenTheNextBegins() {
        assertEquals(NowNext(midnight, small), Program.nowNext(schedule, "main", at(23, 30)))
    }

    @Test
    fun nothingNextOnceTheNightIsOver() {
        assertEquals(NowNext(null, null), Program.nowNext(schedule, "main", at(3, day = 1)))
        // the next night is further than "urmează" looks
        val tomorrow = ScheduleItem("main", "Mâine", at(22, day = 1), at(23, day = 1))
        assertNull(Program.nowNext(schedule + tomorrow, "main", at(3, day = 1)).next)
        assertEquals(tomorrow, Program.nowNext(schedule + tomorrow, "main", at(17, day = 1)).next)
    }

    @Test
    fun theSmallHoursBelongToTheNightBefore() {
        val utc = TimeZone.UTC
        assertEquals(LocalDate(2026, 8, 14), Program.day(small, utc))
        assertEquals(LocalDate(2026, 8, 14), Program.day(midnight, utc))
        assertEquals(listOf(LocalDate(2026, 8, 14)), Program.days(schedule, utc))
        assertEquals(schedule, Program.onDay(schedule, LocalDate(2026, 8, 14), utc))
        assertEquals(LocalDate(2026, 8, 14), Program.currentDay(schedule, at(2, day = 1), utc))
        assertEquals(listOf("main", "second"), Program.stages(schedule))
    }

    @Test
    fun theReminderAddsTheWalk() {
        assertEquals(0, Program.walkMinutes(null))
        assertEquals(1, Program.walkMinutes(30))
        assertEquals(10, Program.walkMinutes(600))
        assertEquals(11, Program.walkMinutes(610))
        // 10 minutes ahead and 11 minutes of walking: 21 minutes before the start
        assertEquals(late.start - 21 * 60, Program.remindAt(late, 10, 610))
        assertEquals(late.start - 10 * 60, Program.remindAt(late, 10, null))
    }

    @Test
    fun aReminderIsDueOnceAndNeverAfterTheStart() {
        val program = MyProgram("e", picked = listOf(Program.key(midnight)), reminders = listOf(Program.key(midnight)), leadMin = 10)
        val walk = { _: ScheduleItem -> 300 }
        // 10 minutes plus 5 of walking
        assertEquals(emptyList(), Program.due(program, schedule, midnight.start - 16 * 60, walk))
        assertEquals(listOf(midnight), Program.due(program, schedule, midnight.start - 15 * 60, walk))
        assertEquals(listOf(midnight), Program.due(program, schedule, midnight.start - 60, walk))
        assertEquals(emptyList(), Program.due(program, schedule, midnight.start, walk))
        val rung = program.copy(fired = listOf(Program.key(midnight)))
        assertEquals(emptyList(), Program.due(rung, schedule, midnight.start - 60, walk))
        // further away, it rings earlier
        assertEquals(listOf(midnight), Program.due(program, schedule, midnight.start - 30 * 60) { 1200 })
    }

    @Test
    fun theWalkIsOnlyAskedForASetThatIsClose() {
        val program = MyProgram("e", reminders = listOf(Program.key(late)), leadMin = 10)
        var asked = 0
        val walk = { _: ScheduleItem ->
            asked++
            300
        }
        assertEquals(emptyList(), Program.due(program, schedule, late.start - 3 * 3600, walk))
        assertEquals(0, asked)
        assertEquals(listOf(late), Program.due(program, schedule, late.start - 14 * 60, walk))
        assertEquals(1, asked)
    }

    @Test
    fun thePlanFitsOneChatMessage() {
        val many = (0 until 30).map { ScheduleItem("main", "Set cu un titlu destul de lung $it", at(18) + it * 600L, at(18) + it * 600L + 500) }
        val text = Program.planText(many.reversed(), "Planul meu") { "21:00 ${it.title}" }
        assertTrue(text.utf8().size <= 400)
        assertTrue(text.startsWith("Planul meu\n21:00 Set cu un titlu destul de lung 0\n"))
        assertEquals("Planul meu\n21:00 Headliner", Program.planText(listOf(late), "Planul meu") { "21:00 ${it.title}" })
    }
}
