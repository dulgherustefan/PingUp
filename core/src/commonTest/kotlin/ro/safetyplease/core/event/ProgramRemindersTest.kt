package ro.safetyplease.core.event

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.delete
import ro.safetyplease.core.session.FakeNotifications
import ro.safetyplease.core.session.ManualClock
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.distanceMeters
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramRemindersTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val venue = Venue(
        name = "Test",
        bounds = Bounds(44.0, 28.0, 44.01, 28.01),
        zones = listOf(
            Zone("main", "Scena mare", "#336699", square(44.000, 28.000), "stage"),
            Zone("second", "Scena 2", "#336699", square(44.003, 28.000), "stage"),
        ),
    )

    private fun square(lat: Double, lon: Double) =
        listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 0.001), GeoPoint(lat + 0.001, lon + 0.001), GeoPoint(lat + 0.001, lon))

    private val start = 1_760_000_000L + 7 * 3600
    private val set = ScheduleItem("main", "Headliner", start + 3600, start + 5400)
    private val key = Program.key(set)
    private val clock = ManualClock()
    private val notifications = FakeNotifications()

    private fun at(secondsBeforeSet: Long) {
        clock.wall = (set.start - secondsBeforeSet) * 1000
    }

    private fun TestScope.reminders(
        saved: MyProgram = MyProgram(EVENT),
        fix: GeoFix? = null,
        walk: WalkDistance = StraightLine,
        schedule: List<ScheduleItem> = listOf(set),
    ): ProgramReminders {
        val store = JsonStore(tmp.root / "program.json", MyProgram.serializer(), saved, backgroundScope)
        return ProgramReminders(
            backgroundScope, store, EVENT, schedule, venue, clock, { Settings() }, { fix }, { walk },
        ) { item, walkMin -> notifications.setReminder(item, venue.zoneName(item.stage), walkMin) }
    }

    /** One tick of the reminder loop, 30 seconds of virtual time later. */
    private fun TestScope.tick() {
        advanceTimeBy(30_001)
        runCurrent()
    }

    private val rung get() = notifications.posted.filter { it.startsWith("set ") }

    @Test
    fun aReminderRingsOnceAcrossTheTicks() = runTest {
        val program = reminders()
        program.setLead(10)
        program.toggleReminder(set)
        runCurrent()
        at(11 * 60)
        tick()
        assertEquals(emptyList(), rung)
        // 10 minutes ahead, no walk known
        at(10 * 60)
        tick()
        assertEquals(listOf("set Headliner Scena mare 0"), rung)
        for (minutes in listOf(9L, 5L, 1L)) {
            at(minutes * 60)
            tick()
        }
        assertEquals(1, rung.size)
        assertTrue(key in program.state.value.fired)
    }

    @Test
    fun theWalkMakesItRingEarlierAndIsNamed() = runTest {
        val stage = venue.zone("main")!!.center
        val from = GeoPoint(stage.lat + 0.005, stage.lon)
        val minutes = Program.walkMinutes(distanceMeters(from, stage))
        assertTrue(minutes > 1)
        val program = reminders(fix = GeoFix(from.lat, from.lon, 5f, clock.wallMs()))
        program.toggleReminder(set)
        runCurrent()
        // a stale fix still says where the phone was last
        at((10L + minutes) * 60 + 60)
        tick()
        assertEquals(emptyList(), rung)
        at((10L + minutes) * 60)
        tick()
        assertEquals(listOf("set Headliner Scena mare $minutes"), rung)
    }

    @Test
    fun aSetThatAlreadyStartedNeverRings() = runTest {
        val program = reminders()
        program.toggleReminder(set)
        runCurrent()
        // 1 minute and then 10 after the start
        for (secondsBefore in listOf(-60L, -600L)) {
            at(secondsBefore)
            tick()
        }
        assertEquals(emptyList(), rung)
        assertNotEquals(emptyList(), program.state.value.reminders)
    }

    @Test
    fun changingTheLeadDoesNotRingAgainWhatRang() = runTest {
        val program = reminders()
        program.toggleReminder(set)
        runCurrent()
        at(10 * 60)
        tick()
        assertEquals(1, rung.size)
        program.setLead(30)
        runCurrent()
        assertEquals(listOf(key), program.state.value.fired)
        at(9 * 60)
        tick()
        at(5 * 60)
        tick()
        assertEquals(1, rung.size)
    }

    @Test
    fun turningTheBellOffAndOnRearmsIt() = runTest {
        val program = reminders()
        program.toggleReminder(set)
        runCurrent()
        at(10 * 60)
        tick()
        assertEquals(1, rung.size)
        program.toggleReminder(set)
        runCurrent()
        at(8 * 60)
        tick()
        assertEquals(1, rung.size)
        program.toggleReminder(set)
        runCurrent()
        assertEquals(emptyList(), program.state.value.fired)
        at(7 * 60)
        tick()
        assertEquals(2, rung.size)
    }

    @Test
    fun anotherEventsPicksAreDropped() = runTest {
        val program = reminders(saved = MyProgram("another", picked = listOf(key), reminders = listOf(key), leadMin = 30))
        runCurrent()
        assertEquals(MyProgram(EVENT), program.state.value)
        assertEquals(emptyList(), program.picked(program.state.value))
        at(20 * 60)
        tick()
        assertEquals(emptyList(), rung)
        // the first change of this event's plan replaces the old one
        program.togglePicked(set)
        runCurrent()
        assertEquals(MyProgram(EVENT, picked = listOf(key)), program.state.value)
    }

    @Test
    fun theWalkIsNotLookedUpForASetFarAway() = runTest {
        var lookups = 0
        val counting = WalkDistance { a, b ->
            lookups++
            distanceMeters(a, b)
        }
        val from = venue.zone("second")!!.center
        val program = reminders(fix = GeoFix(from.lat, from.lon, 5f, clock.wallMs()), walk = counting)
        program.toggleReminder(set)
        runCurrent()
        at(5 * 3600)
        tick()
        tick()
        assertEquals(0, lookups)
        at(40 * 60)
        tick()
        assertTrue(lookups > 0)
    }

    private companion object {
        const val EVENT = "event-1"
    }
}
