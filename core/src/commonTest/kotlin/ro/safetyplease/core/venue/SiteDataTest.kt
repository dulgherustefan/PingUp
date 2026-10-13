package ro.safetyplease.core.venue

import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The site data of venue.json beyond the zones: fences, gates, blocked areas, meeting landmarks, evacuation plans. */
class SiteDataTest {
    private val bundled = repoFile("app/src/main/assets/venue.json").readText()

    private fun square(id: String, lat: Double, lon: Double) =
        Zone(id, id, "#336699", listOf(GeoPoint(lat, lon), GeoPoint(lat, lon + 0.001), GeoPoint(lat + 0.001, lon + 0.001), GeoPoint(lat + 0.001, lon)))

    private val site = Venue(
        "test", Bounds(43.99, 25.99, 44.01, 26.01), listOf(square("a", 44.0, 26.0), square("b", 44.0, 26.002)),
        pois = listOf(
            Poi(Venue.EXIT, 44.0, 25.999, "Vest", number = 1),
            Poi(Venue.EXIT, 44.0, 26.004, "Est", number = 2),
            Poi("info", 44.0005, 26.0015, "Info", number = 3),
            Poi("wc", 44.0005, 26.0016, "Toalete"),
        ),
        evacuation = listOf(EvacuationPlan(1, "A", mapOf("a" to EvacuationExit(1, 2), "b" to EvacuationExit(2)))),
    )

    @Test
    fun aVenueWithoutTheNewFieldsReadsAsBefore() {
        val venue = Venue.parse("""{"name":"x","bounds":{"minLat":0,"minLon":0,"maxLat":1,"maxLon":1},"zones":[]}""")
        val fields = listOf(venue.water, venue.barriers, venue.gates, venue.blocked, venue.meetingLandmarks, venue.evacuation)
        assertTrue(fields.all { it.isEmpty() })
    }

    @Test
    fun theBundledVenueHasItsFenceGatesBackstageWaterAndAPlanWithoutProblems() {
        val venue = Venue.parse(bundled, "en")
        assertTrue(venue.water.isNotEmpty() && venue.barriers.size == 2)
        assertEquals(listOf(1, 2, 3, 4), venue.gates.filter { !it.staffOnly }.map { it.number })
        assertEquals("Main gate", venue.gates.first().name)
        assertEquals(listOf("Backstage access"), venue.gates.filter { it.staffOnly }.map { it.name })
        assertEquals(BlockedArea.STAFF, venue.blocked.single().access)
        assertEquals(listOf(8, 9, 11, 12), venue.meetingPlaces().map { it.number })
        val plan = assertNotNull(venue.evacuationPlan(1))
        assertEquals("Plan A: every exit", plan.name)
        assertEquals(venue.zones.map { it.id }.toSet(), plan.exits.keys)
        assertEquals(emptyList(), EvacuationPlans.problems(venue))
        assertEquals(listOf(plan), EvacuationPlans.usable(venue))
        // nobody stands in the sea: no zone corner, point or gate of the site
        val inWater = { p: GeoPoint -> venue.water.any { pointInPolygon(p.lat, p.lon, it) } }
        assertTrue(venue.zones.flatMap { it.polygon }.none(inWater) && venue.pois.map { it.point }.none(inWater))
    }

    @Test
    fun everyNumberedLandmarkIsForMeetingUnlessTheOrganiserChose() {
        assertEquals(listOf(1, 2, 3), site.meetingPlaces().map { it.number })
        assertEquals(listOf(3), site.copy(meetingLandmarks = listOf(3, 9)).meetingPlaces().map { it.number })
    }

    @Test
    fun aGoodPlanHasNoProblem() {
        assertEquals(emptyList(), EvacuationPlans.problems(site))
    }

    @Test
    fun aPlanMustCoverEveryZoneWithExitsOfTheVenue() {
        fun problems(vararg exits: Pair<String, EvacuationExit>, id: Int = 1) =
            EvacuationPlans.problems(site.copy(evacuation = listOf(EvacuationPlan(id, "P", mapOf(*exits))))).map { it.kind to it.zone }
        assertEquals(listOf(PlanProblem.Kind.ZONE_MISSING to "b"), problems("a" to EvacuationExit(1, 2)))
        assertEquals(
            listOf(PlanProblem.Kind.UNKNOWN_ZONE to "c"),
            problems("a" to EvacuationExit(1, 2), "b" to EvacuationExit(2), "c" to EvacuationExit(1)),
        )
        assertEquals(listOf(PlanProblem.Kind.NOT_AN_EXIT to "a"), problems("a" to EvacuationExit(3, 2), "b" to EvacuationExit(2)), "an info point")
        assertEquals(listOf(PlanProblem.Kind.NOT_AN_EXIT to "a"), problems("a" to EvacuationExit(1, 9), "b" to EvacuationExit(2)))
        assertEquals(listOf(PlanProblem.Kind.SAME_EXIT to "a"), problems("a" to EvacuationExit(1, 1), "b" to EvacuationExit(2)))
        assertEquals(listOf(PlanProblem.Kind.ID to ""), problems("a" to EvacuationExit(1, 2), "b" to EvacuationExit(2), id = 256))
    }

    @Test
    fun aPlanWithASingleExitIsRejected() {
        val one = site.copy(evacuation = listOf(EvacuationPlan(2, "Doar vest", mapOf("a" to EvacuationExit(1), "b" to EvacuationExit(1)))))
        assertEquals(listOf(PlanProblem(2, PlanProblem.Kind.SINGLE_EXIT)), EvacuationPlans.problems(one))
        assertEquals(emptyList(), EvacuationPlans.usable(one))
    }

    @Test
    fun twoPlansWithOneIdAreBothUnusable() {
        val plan = site.evacuation.single()
        val twice = site.copy(evacuation = listOf(plan, plan.copy(name = "B")))
        assertEquals(listOf(PlanProblem(1, PlanProblem.Kind.DUPLICATE_ID)), EvacuationPlans.problems(twice))
        assertEquals(emptyList(), EvacuationPlans.usable(twice))
    }

    @Test
    fun theStraightLineIsTheDistanceAsTheCrowFlies() {
        val from = GeoPoint(44.0, 26.0)
        val to = GeoPoint(44.001, 26.0)
        val walk: WalkDistance = StraightLine
        assertEquals(distanceMeters(from, to), walk.meters(from, to))
        assertEquals(0, walk.meters(from, from))
    }
}
