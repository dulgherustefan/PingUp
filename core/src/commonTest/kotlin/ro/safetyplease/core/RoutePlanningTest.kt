package ro.safetyplease.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.IncidentData
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.event.MyProgram
import ro.safetyplease.core.event.ProgramReminders
import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.incidents.AlertLevel
import ro.safetyplease.core.incidents.AlertPost
import ro.safetyplease.core.incidents.AlertReason
import ro.safetyplease.core.incidents.Route
import ro.safetyplease.core.mesh.SimNet
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.position.Regasire
import ro.safetyplease.core.position.Search
import ro.safetyplease.core.session.FakeNotifications
import ro.safetyplease.core.session.FakePlatform
import ro.safetyplease.core.session.ManualClock
import ro.safetyplease.core.session.StaffChain
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.Zone
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Walks planned with A* (hundreds of them on a large site) go to the planner the session gives, Dispatchers.Default,
 * so they never hold the single mesh thread; what they lead to happens back on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutePlanningTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    /** The test's scheduler, telling whether what runs now was dispatched to it. */
    private class Planner(scheduler: TestCoroutineScheduler) : CoroutineDispatcher() {
        private val inner = StandardTestDispatcher(scheduler, "planner")
        var running = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) = inner.dispatch(
            context,
            object : Runnable {
                override fun run() {
                    running = true
                    try {
                        block.run()
                    } finally {
                        running = false
                    }
                }
            },
        )
    }

    /** Straight lines, noting for each walk whether it was planned in the planner. */
    private fun walks(planner: Planner, planned: MutableList<Boolean>) = WalkDistance { from, to ->
        planned += planner.running
        StraightLine.meters(from, to)
    }

    private fun square(lat: Double, lon: Double) =
        listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 0.001), GeoPoint(lat + 0.001, lon + 0.001), GeoPoint(lat + 0.001, lon))

    private val venue = Venue(
        "Test", Bounds(BOX_LAT, BOX_LON, BOX_LAT + 0.01, BOX_LON + 0.01),
        listOf(Zone("main", "Scena", "#336699", square(BOX_LAT + 0.005, BOX_LON + 0.005), "stage")),
        pois = listOf(
            Poi("info", BOX_LAT + 0.001, BOX_LON + 0.001, number = 1),
            Poi("info", BOX_LAT + 0.003, BOX_LON + 0.001, number = 2),
            Poi("dea", BOX_LAT + 0.004, BOX_LON + 0.004),
        ),
    )

    @Test
    fun aLostSessionPlansTheWalksToTheMeetingPlacesInThePlanner() = runTest {
        val clock = TestClock(testScheduler)
        val net = SimNet(backgroundScope)
        val event = TestEvent()
        val a = TestPhone("A", this, net, clock, tmp.root, event, 60)
        val b = TestPhone("B", this, net, clock, tmp.root, event, 61)
        a.befriend(b)
        net.start()
        advanceTimeBy(30_000)
        a.chat.createGroup("Gașca", listOf(b.nodeId))
        advanceTimeBy(5_000)
        val planner = Planner(testScheduler)
        val planned = mutableListOf<Boolean>()
        val reg = Regasire(
            backgroundScope, a.chat, a.positions, clock, venue, a.nodeId,
            JsonStore(tmp.root / "searches.json", ListSerializer(Search.serializer()), emptyList(), backgroundScope),
            walk = { walks(planner, planned) }, mapState = { MapState.OPEN },
            myPosition = { GeoFix(BOX_LAT + 0.0015, BOX_LON + 0.001, 5f, clock.wallMs()) }, battery = { 50 }, frame = { a.positionFrame },
            planner = planner,
        )
        assertTrue(reg.imLost(Conversations.group(a.groups.value.single().id)))
        assertTrue(planned.isNotEmpty() && planned.all { it }, "$planned")
        assertEquals(1, reg.target.value?.landmark, "the nearer landmark")
    }

    @Test
    fun aReminderPlansItsWalkInThePlannerAndRingsBackWhereItRuns() = runTest {
        val planner = Planner(testScheduler)
        val planned = mutableListOf<Boolean>()
        val rang = mutableListOf<Boolean>()
        val set = ScheduleItem("main", "Headliner", 1_760_000_000L + 7200, 1_760_000_000L + 9000)
        val clock = ManualClock()
        val store = JsonStore(tmp.root / "program.json", MyProgram.serializer(), MyProgram("fest"), backgroundScope)
        val program = ProgramReminders(
            backgroundScope, store, "fest", listOf(set), venue, clock, { Settings() },
            fix = { GeoFix(BOX_LAT + 0.001, BOX_LON + 0.001, 5f, clock.wallMs()) }, walk = { walks(planner, planned) }, planner = planner,
        ) { _, _ -> rang += planner.running }
        program.toggleReminder(set)
        runCurrent()
        clock.wall = (set.start - 5 * 60) * 1000
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(planned.isNotEmpty() && planned.all { it }, "$planned")
        assertEquals(listOf(false), rang)
    }

    @Test
    fun aRespondersAlertPlansTheWalksByTheDefibrillatorInThePlanner() = runTest {
        val planner = Planner(testScheduler)
        val planned = mutableListOf<Boolean>()
        val notifications = FakeNotifications()
        val settings = JsonStore(tmp.root / "settings.json", Settings.serializer(), Settings(role = Role.STAFF, teamName = "Prim ajutor 1"), backgroundScope)
        val chain = StaffChain(
            backgroundScope, backgroundScope, FakePlatform(tmp), notifications, TestClock(testScheduler), settings,
            MutableStateFlow(IncidentData()), venue, { walks(planner, planned) }, { _, _ -> true },
            tmp.root / "density.json", tmp.root / "handover.json", tmp.root / "staff_chain.json", tmp.root / "export", MutableStateFlow(false),
            planner = planner,
        )
        val incident = StaffIncident(
            incidentId = "0a".repeat(16), category = 1, severity = 3, zone = "main", lat = BOX_LAT + 0.005, lon = BOX_LON + 0.005,
            reportedAt = 0L, receivedAt = 0L, hops = 1, unconscious = true,
        )
        chain.post(AlertPost(incident, Route(AlertLevel.INSISTENT, AlertReason.RESPONDER), fullScreen = true, silent = false))
        assertTrue(planned.isNotEmpty() && planned.all { it }, "$planned")
        assertEquals(listOf("alert ${incident.incidentId}"), notifications.posted)
    }
}
