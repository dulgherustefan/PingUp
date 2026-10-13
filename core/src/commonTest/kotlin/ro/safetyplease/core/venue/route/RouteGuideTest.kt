package ro.safetyplease.core.venue.route

import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.venue.Gate
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.route.Sites.at
import ro.safetyplease.core.venue.route.Sites.crosses
import ro.safetyplease.core.venue.route.Sites.zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RouteGuideTest {
    /** East 100 m, then left, north 100 m. */
    private val route = Route(
        points = listOf(at(0.0, 0.0), at(100.0, 0.0), at(100.0, 100.0)),
        meters = 200,
        seconds = 200,
        steps = listOf(RouteStep(at(100.0, 0.0), 100, Turn.LEFT, Anchor.NONE), RouteStep(at(100.0, 100.0), 200, Turn.ARRIVE, Anchor.NONE)),
    )

    @Test
    fun onTheRouteItTellsTheNextStepAndWhatIsLeft() {
        val p = RouteGuide(route).update(at(10.0, 2.0), 5f, 0)
        assertEquals(0, p.step)
        assertEquals(90, p.toStepM)
        assertEquals(190, p.remainingM)
        assertEquals(190, p.remainingSec)
        assertEquals(2, p.offRouteM)
        assertTrue(!p.offRoute && !p.reroute && !p.arrived)
        assertNull(p.event)
        assertEquals(at(100.0, 0.0), p.toward)
    }

    @Test
    fun aTurnComingIsSaidOnceAndAPassedTurnLeadsToTheNext() {
        val guide = RouteGuide(route)
        assertNull(guide.update(at(70.0, 0.0), 5f, 0).event)
        assertEquals(GuideEvent.TURN_SOON, guide.update(at(85.0, 0.0), 5f, 1_000).event)
        assertNull(guide.update(at(90.0, 0.0), 5f, 2_000).event)
        // a few metres from the corner the arrow already points up the next leg
        assertEquals(at(100.0, 100.0), guide.update(at(97.0, 0.0), 5f, 3_000).toward)
        val after = guide.update(at(100.0, 10.0), 5f, 4_000)
        assertEquals(1, after.step)
        assertEquals(90, after.toStepM)
    }

    @Test
    fun offTheRouteForTwentySecondsAsksForANewOneOnce() {
        val guide = RouteGuide(route)
        val off = at(50.0, 30.0)
        val first = guide.update(off, 5f, 0)
        assertTrue(first.offRoute && !first.reroute)
        assertNull(first.event)
        assertTrue(!guide.update(off, 5f, 19_000).reroute)
        val late = guide.update(off, 5f, 20_000)
        assertTrue(late.reroute)
        assertEquals(GuideEvent.OFF_ROUTE, late.event)
        assertNull(guide.update(off, 5f, 21_000).event)
        // back on the route the clock starts again
        assertTrue(!guide.update(at(50.0, 0.0), 5f, 22_000).offRoute)
        assertTrue(!guide.update(off, 5f, 23_000).reroute)
        assertTrue(!guide.update(off, 5f, 42_000).reroute)
    }

    @Test
    fun aVagueFixMustBeFartherOffToCountAsOff() {
        assertTrue(!RouteGuide(route).update(at(50.0, 30.0), 20f, 0).offRoute)
        assertTrue(RouteGuide(route).update(at(50.0, 45.0), 20f, 0).offRoute)
    }

    @Test
    fun arrivalIsWithinTheFixAccuracyOfTheEnd() {
        val guide = RouteGuide(route)
        assertTrue(!guide.update(at(100.0, 85.0), 5f, 0).arrived)
        val there = guide.update(at(100.0, 95.0), 3f, 1_000)
        assertTrue(there.arrived)
        assertEquals(GuideEvent.ARRIVED, there.event)
        assertNull(guide.update(at(100.0, 96.0), 3f, 2_000).event)
        assertTrue(RouteGuide(route).update(at(100.0, 88.0), 15f, 0).arrived)
    }

    @Test
    fun theTimeLeftKeepsTheRoutePace() {
        val crowded = RouteGuide(route.copy(seconds = 400)).update(at(50.0, 0.0), 5f, 0)
        assertEquals(150, crowded.remainingM)
        assertEquals(300, crowded.remainingSec)
    }
}

class WalkNavigationTest {
    private val middle = zone("middle", 100.0, -40.0, 200.0, 150.0)
    private val router = WalkRouter(Sites.empty().copy(zones = listOf(middle)))
    private val from = at(50.0, 50.0)
    private val goal = WalkGoal.To(at(250.0, 50.0))

    private fun guiding(state: WalkState) = assertIs<WalkState.Guiding>(state)

    @Test
    fun withoutAPositionOffTheMapOrWithoutAGoalThereIsNoRoute() {
        val nav = WalkNavigation(router, staff = false)
        assertEquals(WalkState.NoPosition, nav.update(null, 0f, 0, MapState.OPEN, goal))
        assertEquals(WalkState.Outside, nav.update(at(5_000.0, 5_000.0), 5f, 0, MapState.OPEN, goal))
        assertEquals(WalkState.NoGoal, nav.update(from, 5f, 0, MapState.OPEN, null))
    }

    @Test
    fun theRouteStaysUntilTheMapStateChanges() {
        val nav = WalkNavigation(router, staff = false)
        val first = guiding(nav.update(from, 5f, 0, MapState.OPEN, goal)).route
        assertSame(first, guiding(nav.update(at(70.0, 60.0), 5f, 1_000, MapState.OPEN, goal)).route)
        val closed = MapState(mapOf("middle" to ZoneState.CLOSED))
        val around = guiding(nav.update(at(70.0, 60.0), 5f, 2_000, closed, goal)).route
        assertNotSame(first, around)
        assertTrue(!crosses(around, middle.polygon))
    }

    @Test
    fun aGoalThatMovesFarGetsANewRoute() {
        val nav = WalkNavigation(router, staff = false)
        val first = guiding(nav.update(from, 5f, 0, MapState.OPEN, goal)).route
        assertSame(first, guiding(nav.update(from, 5f, 1_000, MapState.OPEN, WalkGoal.To(at(250.0, 60.0)))).route)
        val moved = guiding(nav.update(from, 5f, 2_000, MapState.OPEN, WalkGoal.To(at(250.0, 120.0)))).route
        assertNotSame(first, moved)
    }

    @Test
    fun offTheRouteLongEnoughTheWalkIsPlannedFromWhereThePersonIs() {
        val nav = WalkNavigation(router, staff = false)
        val first = guiding(nav.update(from, 5f, 0, MapState.OPEN, goal)).route
        val off = at(60.0, 150.0)
        assertSame(first, guiding(nav.update(off, 5f, 1_000, MapState.OPEN, goal)).route)
        val again = guiding(nav.update(off, 5f, 21_000, MapState.OPEN, goal))
        assertNotSame(first, again.route)
        assertEquals(GuideEvent.OFF_ROUTE, again.progress.event)
        assertTrue(again.progress.offRouteM < 2)
    }

    @Test
    fun theNearestOpenPlaceIsChosenAgainWhenOneCloses() {
        fun water(x: Double, number: Int) = at(x, 180.0).let { Poi("water", it.lat, it.lon, "", number = number) }
        val site = WalkRouter(Sites.empty().copy(pois = listOf(water(80.0, 1), water(280.0, 2))))
        val nav = WalkNavigation(site, staff = false)
        assertEquals(1, guiding(nav.update(from, 5f, 0, MapState.OPEN, WalkGoal.NearestOpen("water"))).destination?.number)
        assertEquals(2, guiding(nav.update(from, 5f, 1_000, MapState.OPEN, WalkGoal.NearestOpen("water", setOf(1)))).destination?.number)
    }

    @Test
    fun anUnreachableGoalIsSaidSo() {
        val fence = listOf(at(150.0, -40.0), at(150.0, 240.0))
        val shut = WalkRouter(Sites.empty().copy(barriers = listOf(fence)))
        val nav = WalkNavigation(shut, staff = false)
        assertEquals(WalkState.Unreachable, nav.update(from, 5f, 0, MapState.OPEN, goal))
        assertEquals(WalkState.Unreachable, nav.update(at(55.0, 50.0), 5f, 1_000, MapState.OPEN, goal))
        val gate = at(150.0, 20.0).let { Gate(it.lat, it.lon) }
        val open = WalkNavigation(WalkRouter(shut.venue.copy(gates = listOf(gate))), staff = false)
        guiding(open.update(from, 5f, 0, MapState.OPEN, goal))
    }
}
