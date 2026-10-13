package ro.safetyplease.core.venue.route

import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import ro.safetyplease.core.venue.BlockedArea
import ro.safetyplease.core.venue.Gate
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.parse
import ro.safetyplease.core.venue.route.Sites.at
import ro.safetyplease.core.venue.route.Sites.crosses
import ro.safetyplease.core.venue.route.Sites.rect
import ro.safetyplease.core.venue.route.Sites.zone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalkRouterTest {
    /** A fence across the whole grid at x = 150, margin included, with [gates] in it. */
    private val fence = listOf(at(150.0, -40.0), at(150.0, 240.0))

    private fun fenced(vararg gates: Gate) = Sites.empty().copy(barriers = listOf(fence), gates = gates.toList())

    private fun gate(y: Double, staffOnly: Boolean = false, number: Int = 0, name: String = "Poarta") =
        at(150.0, y).let { Gate(it.lat, it.lon, name, number, staffOnly) }

    @Test
    fun aRouteNeverCrossesWaterAFenceOrABlockedArea() {
        val lake = rect(90.0, 40.0, 140.0, 160.0)
        val backstage = rect(180.0, 60.0, 230.0, 240.0)
        val venue = fenced(gate(20.0)).copy(water = listOf(lake), blocked = listOf(BlockedArea(backstage, BlockedArea.NONE)))
        val route = assertNotNull(WalkRouter(venue).route(at(60.0, 100.0), at(260.0, 150.0)))
        assertTrue(!crosses(route, lake) && !crosses(route, backstage))
        val crossings = Sites.fenceCrossings(route, fence)
        assertEquals(1, crossings.size)
        assertTrue(Sites.distance(venue.gates.single().point, crossings[0].first, crossings[0].second) <= Gate.REACH_M)
    }

    @Test
    fun aGateIsTheOnlyWayThroughAFence() {
        val from = at(140.0, 180.0)
        val to = at(160.0, 180.0)
        assertNull(WalkRouter(fenced()).route(from, to))
        assertNull(WalkRouter(fenced()).distance({ MapState.OPEN }) { false }.meters(from, to))

        val route = assertNotNull(WalkRouter(fenced(gate(20.0, number = 3, name = "Poarta est"))).route(from, to))
        // down to the gate and back up, not the 20 m across
        assertTrue(route.meters in 300..340, "${route.meters} m")
        val step = route.steps.first { it.anchor.kind == AnchorKind.GATE }
        assertEquals(Anchor(AnchorKind.GATE, 3, "Poarta est"), step.anchor)
        assertTrue(distanceMeters(step.point, at(150.0, 20.0)) <= Gate.REACH_M)
    }

    @Test
    fun aStaffGateAndAStaffAreaOpenOnlyForStaff() {
        val from = at(140.0, 180.0)
        val to = at(160.0, 180.0)
        val router = WalkRouter(fenced(gate(180.0, staffOnly = true), gate(20.0)))
        assertTrue(router.route(from, to)!!.meters > 300)
        assertTrue(router.route(from, to, staff = true)!!.meters < 30)

        val wall = rect(130.0, -40.0, 170.0, 150.0)
        val staffOnly = WalkRouter(Sites.empty().copy(blocked = listOf(BlockedArea(wall, BlockedArea.STAFF))))
        val a = at(100.0, 50.0)
        val b = at(200.0, 50.0)
        val public = staffOnly.route(a, b)!!
        assertTrue(public.meters > 200 && !crosses(public, wall))
        assertTrue(staffOnly.route(a, b, staff = true)!!.meters < 105)

        val nobody = WalkRouter(Sites.empty().copy(blocked = listOf(BlockedArea(wall, BlockedArea.NONE))))
        assertTrue(!crosses(nobody.route(a, b, staff = true)!!, wall))
    }

    @Test
    fun aClosedZoneIsWalkedAroundAndOutOf() {
        val middle = zone("middle", 100.0, -40.0, 200.0, 150.0)
        val router = WalkRouter(Sites.empty().copy(zones = listOf(middle)))
        val from = at(50.0, 50.0)
        val to = at(250.0, 50.0)
        val closed = MapState(mapOf("middle" to ZoneState.CLOSED))
        assertTrue(abs(router.route(from, to)!!.meters - 200) <= 2)

        val around = router.route(from, to, closed)!!
        assertTrue(around.meters > 300 && !crosses(around, middle.polygon))

        // from inside a closed zone the walk still leads out
        val out = assertNotNull(router.route(at(150.0, 100.0), to, closed))
        assertTrue(out.meters < 150)
        assertEquals(around.meters, WalkRouter(router.venue).distance({ closed }) { false }.meters(from, to))
    }

    @Test
    fun aCrowdedZoneCostsThreeTimesAndSlowsTheWalk() {
        val from = at(50.0, 50.0)
        val to = at(250.0, 50.0)
        val crowd = MapState(mapOf("crowd" to ZoneState.CROWDED))

        val narrow = WalkRouter(Sites.empty().copy(zones = listOf(zone("crowd", 100.0, 0.0, 200.0, 100.0))))
        val detour = narrow.route(from, to, crowd)!!
        assertTrue(detour.meters in 205..300, "${detour.meters} m")

        // no way around: through it, at a third of the pace
        val wide = WalkRouter(Sites.empty().copy(zones = listOf(zone("crowd", 100.0, -40.0, 200.0, 240.0))))
        val through = wide.route(from, to, crowd)!!
        assertTrue(abs(through.meters - 200) <= 2)
        assertTrue(through.seconds in 390..410, "${through.seconds} s")

        val exitOnly = wide.route(from, to, MapState(mapOf("crowd" to ZoneState.EXIT_ONLY)))!!
        assertTrue(abs(exitOnly.seconds - 200) <= 2)
    }

    @Test
    fun theTimeIsAtWalkingPaceAndShownAsARange() {
        val route = WalkRouter(Sites.empty()).route(at(50.0, 50.0), at(250.0, 50.0))!!
        assertTrue(abs(route.meters - 200) <= 2 && abs(route.seconds - 200) <= 2)
        assertEquals(4..5, Route.minutes(route.seconds))
        assertEquals(10..15, Route.minutes(600))
        assertEquals(1..2, Route.minutes(20))
    }

    /** A block north-west of the corner at (200, 70): the walk from below it to its east side bends left there. */
    private val corner = Sites.empty().copy(blocked = listOf(BlockedArea(rect(-40.0, 70.0, 200.0, 240.0), BlockedArea.NONE)))

    @Test
    fun aTurnIsToldByTheNearestNumberedLandmarkAndTheWayItBends() {
        val venue = corner.copy(pois = listOf(Poi("info", at(205.0, 60.0).lat, at(205.0, 60.0).lon, "Info", number = 14), Poi("wc", at(150.0, 30.0).lat, at(150.0, 30.0).lon, "WC")))
        val router = WalkRouter(venue)
        val there = router.route(at(50.0, 50.0), at(250.0, 150.0))!!
        assertEquals(2, there.steps.size)
        assertEquals(Turn.LEFT, there.steps[0].turn)
        assertEquals(Anchor(AnchorKind.LANDMARK, 14, "Info"), there.steps[0].anchor)
        assertEquals(Turn.ARRIVE, there.steps.last().turn)
        assertEquals(there.meters, there.steps.last().alongM)

        // the same corner walked the other way bends right: the geometry decides, not a compass
        val back = router.route(at(250.0, 150.0), at(50.0, 50.0))!!
        assertEquals(listOf(Turn.RIGHT, Turn.ARRIVE), back.steps.map { it.turn })
    }

    @Test
    fun withoutALandmarkNearbyATurnIsToldByItsZone() {
        val far = corner.copy(
            zones = listOf(zone("food", 180.0, 0.0, 300.0, 70.0)),
            pois = listOf(Poi("info", at(30.0, 30.0).lat, at(30.0, 30.0).lon, "Info", number = 1)),
        )
        val route = WalkRouter(far).route(at(50.0, 50.0), at(250.0, 150.0))!!
        assertEquals(Anchor(AnchorKind.ZONE, name = "food"), route.steps[0].anchor)
        assertEquals(Anchor.NONE, WalkRouter(corner).route(at(50.0, 50.0), at(250.0, 150.0))!!.steps[0].anchor)
    }

    @Test
    fun aStraightWalkHasOnlyItsArrival() {
        val route = WalkRouter(Sites.empty()).route(at(20.0, 20.0), at(280.0, 180.0))!!
        assertEquals(2, route.points.size)
        assertEquals(listOf(Turn.ARRIVE), route.steps.map { it.turn })
    }

    @Test
    fun theNearestOpenPlaceIsNearestByWalkingAndSkipsWhatIsClosed() {
        fun water(x: Double, y: Double, number: Int) = at(x, y).let { Poi("water", it.lat, it.lon, "Apă $number", number = number) }
        val venue = fenced(gate(20.0)).copy(
            // 1 is a few metres away but behind the fence; 2 farther in a straight line, nearer on foot
            pois = listOf(water(160.0, 180.0, 1), water(80.0, 120.0, 2), water(40.0, 100.0, 3), Poi("wc", at(141.0, 180.0).lat, at(141.0, 180.0).lon)),
            zones = listOf(zone("west", 60.0, 110.0, 100.0, 130.0)),
        )
        val router = WalkRouter(venue)
        val from = at(140.0, 180.0)
        assertEquals(2, router.nearestOpen(from, "water")!!.number)
        assertEquals(3, router.nearestOpen(from, "water", closedPoints = setOf(2))!!.number)
        assertEquals(3, router.nearestOpen(from, "water", MapState(mapOf("west" to ZoneState.CLOSED)))!!.number)
        val found = router.nearestOpen(from, "water")!!
        assertEquals("Apă 2", found.name)
        assertEquals(found.route.meters, router.route(from, at(80.0, 120.0))!!.meters)
        assertNull(router.nearestOpen(from, "dea"))
    }

    @Test
    fun aMedicalZoneCountsAsAMedicalPlace() {
        val venue = Sites.empty().copy(zones = listOf(zone("med", 200.0, 0.0, 240.0, 40.0, type = "medical")))
        val found = WalkRouter(venue).nearestOpen(at(20.0, 20.0), "medical")!!
        assertEquals("med", found.name)
        assertNull(WalkRouter(venue).nearestOpen(at(20.0, 20.0), "medical", MapState(mapOf("med" to ZoneState.CLOSED))))
    }

    @Test
    fun offTheGridTheDistanceIsAStraightLineAndTheRouterSaysSo() {
        val router = WalkRouter(Sites.empty())
        val home = at(5_000.0, 5_000.0)
        assertTrue(!router.covers(home) && router.covers(at(-20.0, -20.0)))
        assertNull(router.route(home, at(50.0, 50.0)))
        assertEquals(distanceMeters(home, at(50.0, 50.0)), router.distance({ MapState.OPEN }) { false }.meters(home, at(50.0, 50.0)))
    }

    @Test
    fun aPointInTheWaterIsMovedToTheShore() {
        val lake = rect(100.0, 50.0, 200.0, 150.0)
        val router = WalkRouter(Sites.empty().copy(water = listOf(lake)))
        val route = assertNotNull(router.route(at(50.0, 100.0), at(110.0, 100.0)))
        assertTrue(!crosses(route, lake) && route.meters in 40..60)
        assertNull(router.route(at(50.0, 100.0), at(150.0, 100.0)))
    }

    @Test
    fun theBundledSiteIsEnteredThroughItsMainGate() {
        val venue = Venue.parse(repoFile("app/src/main/assets/venue.json").readText(), "ro")
        val router = WalkRouter(venue)
        val outside = GeoPoint(43.94785, 28.6344)
        val food = venue.zone("food")!!.center
        val route = assertNotNull(router.route(outside, food))
        assertEquals(Anchor(AnchorKind.GATE, 1, "Poarta principală"), route.steps.first().anchor)
        val backstage = venue.blocked.single().polygon
        val stage = venue.zone("main-stage")!!.center
        val inside = GeoPoint(43.95093, 28.6351)
        // the public stops at the backstage fence, staff walk in through their gate
        assertTrue(!crosses(router.route(stage, inside)!!, backstage))
        assertTrue(crosses(router.route(stage, inside, staff = true)!!, backstage))
    }

    @Test
    fun aRouteOnFiftyFiveThousandCellsExpandsEachCellAtMostOnce() {
        val router = WalkRouter(Sites.zigzag())
        val size = router.grid(false).size
        assertTrue(size in 50_000..60_000, "$size cells")
        val probe = assertNotNull(router.probe(Sites.zigzagEnds.first, Sites.zigzagEnds.second))
        assertTrue(probe.expanded <= EXPANDED_ZIGZAG, "${probe.expanded} cells expanded")
        assertTrue(router.route(Sites.zigzagEnds.first, Sites.zigzagEnds.second)!!.meters > 7 * 600)

        // across an open site A* goes nearly straight to the goal
        val open = WalkRouter(Sites.empty(640.0, 640.0))
        val straight = assertNotNull(open.probe(at(10.0, 10.0), at(630.0, 630.0)))
        assertTrue(straight.expanded < 3 * straight.cost, "${straight.expanded} cells expanded for ${straight.cost}")
    }

    @Test
    fun aGateDrawnOffTheFenceLineStillLetsTheWalkThrough() {
        val center = Pair(150.0, 100.0)
        for (angle in listOf(0.0, 20.0, 45.0, 60.0, 90.0, 135.0)) {
            val ux = cos(angle * PI / 180)
            val uy = sin(angle * PI / 180)
            fun along(t: Double, n: Double) = at(center.first + t * ux - n * uy, center.second + t * uy + n * ux)
            val fence = listOf(along(-1_000.0, 0.0), along(1_000.0, 0.0))
            val from = along(20.0, -40.0)
            val to = along(-20.0, 40.0)
            val shut = Sites.empty().copy(barriers = listOf(fence))
            assertNull(WalkRouter(shut).route(from, to), "no gate at $angle°")
            for (offset in listOf(0.0, 2.5, 4.9, -2.5, -4.9)) {
                val g = along(0.0, offset)
                val site = shut.copy(gates = listOf(Gate(g.lat, g.lon)))
                val route = assertNotNull(WalkRouter(site).route(from, to), "gate $offset m off a fence at $angle°")
                val crossings = Sites.fenceCrossings(route, fence)
                assertEquals(1, crossings.size, "gate $offset m off a fence at $angle°")
                val (a, b) = crossings[0]
                assertTrue(Sites.distance(g, a, b) <= 2 * Gate.REACH_M + WalkGrid.CELL_M, "gate $offset m off a fence at $angle°")
            }
        }
    }

    @Test
    fun aStarFindsTheCheapestWalkAsDijkstraDoes() {
        var compared = 0
        Sites.randomSites(seed = 7, count = 25).forEach { (venue, state) ->
            val router = WalkRouter(venue)
            val grid = router.grid(false)
            Sites.randomPairs(seed = venue.hashCode(), count = 4).forEach { (from, to) ->
                val probe = router.probe(from, to, state) ?: return@forEach
                val expected = Sites.dijkstra(grid, Costs(grid, state, grid.cellAt(from)), probe.start, probe.goal)
                assertTrue(abs(probe.cost - expected) <= 1e-3 * max(1.0, expected), "A* ${probe.cost}, Dijkstra $expected")
                compared++
            }
        }
        assertTrue(compared >= 40, "$compared walks compared")
    }

    @Test
    fun aSmoothedRouteNeverCutsThroughAShutCell() {
        var checked = 0
        Sites.randomSites(seed = 11, count = 25).forEach { (venue, state) ->
            val router = WalkRouter(venue)
            val grid = router.grid(false)
            Sites.randomPairs(seed = venue.hashCode() + 1, count = 4).forEach { (from, to) ->
                val route = router.route(from, to, state) ?: return@forEach
                val costs = Costs(grid, state, grid.cellAt(from))
                Sites.samples(route, perMeter = 10).forEach { p ->
                    val cell = grid.cellAt(p)
                    assertTrue(cell >= 0 && costs.of(cell) != WalkRouter.SHUT, "the route crosses a shut cell at ${Sites.x(p)}, ${Sites.y(p)}")
                }
                checked++
            }
        }
        assertTrue(checked >= 40, "$checked routes checked")
    }

    private companion object {
        /** The zigzag's search expands about 42,700 of its 54,000 cells; much more would mean a broken heuristic or heap. */
        const val EXPANDED_ZIGZAG = 45_000
    }
}

