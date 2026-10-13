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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

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
        assertEquals(4..5, route.minutes())
        assertEquals(10..15, route.copy(seconds = 600).minutes())
        assertEquals(1..2, route.copy(seconds = 20).minutes())
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
        assertEquals(found.route.meters, router.route(from, found.point)!!.meters)
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
    fun aRouteOnFiftyFiveThousandCellsTakesUnderFiftyMilliseconds() {
        // 640 m square, fences every 80 m with the gate at alternate ends: the walk zigzags across the whole grid
        val fences = (1..7).map { listOf(at(-40.0, it * 80.0), at(680.0, it * 80.0)) }
        val gates = (1..7).map { at(if (it % 2 == 1) 620.0 else 20.0, it * 80.0).let { p -> Gate(p.lat, p.lon) } }
        val venue = Sites.empty(640.0, 640.0).copy(barriers = fences, gates = gates)
        val router = WalkRouter(venue)
        assertTrue(router.grid(false).size in 50_000..60_000, "${router.grid(false).size} cells")
        val from = at(10.0, 10.0)
        val to = at(630.0, 630.0)
        repeat(5) { router.route(from, to) }
        val clock = TimeSource.Monotonic
        val times = (1..10).map {
            val start = clock.markNow()
            assertNotNull(router.route(from, to))
            start.elapsedNow().inWholeMicroseconds
        }
        println("route on ${router.grid(false).size} cells: median ${times.sorted()[5] / 1000.0} ms, max ${times.max() / 1000.0} ms")
        assertTrue(times.max() < 50_000, "$times µs")
        assertTrue(router.route(from, to)!!.meters > 7 * 600)
    }
}
