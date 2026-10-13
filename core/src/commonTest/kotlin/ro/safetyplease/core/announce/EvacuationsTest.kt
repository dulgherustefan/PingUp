package ro.safetyplease.core.announce

import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.EvacuationExit
import ro.safetyplease.core.venue.EvacuationPlan
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Zone-targeted notices and the organiser's evacuation plans: who gets the alarm, which exit each zone takes in each
 * plan, the alternative when the main exit is closed, and what a notice from before the extensions still does.
 */
class EvacuationsTest {
    private val control = testCrypto.signKeyPair()
    private val credential = Credential(
        Certificate.issue(testCrypto, CertRole.CONTROL, control.publicKey, 0, 0xFFFFFFFFL, "Control", testCrypto.signKeyPair()), control,
    )

    /** Three zones side by side, west to east, an exit in each and a fourth exit north of them all. */
    private fun square(west: Double) =
        listOf(GeoPoint(44.000, west), GeoPoint(44.000, west + 0.001), GeoPoint(44.001, west + 0.001), GeoPoint(44.001, west))

    private val zones = listOf("a", "b", "c").mapIndexed { i, id -> Zone(id, "Zona ${id.uppercase()}", "#336699", square(26.000 + i * 0.001)) }
    private val exits = listOf(
        Poi(Venue.EXIT, 44.0005, 26.0005, "Poarta A", 1),
        Poi(Venue.EXIT, 44.0005, 26.0015, "Poarta B", 2),
        Poi(Venue.EXIT, 44.0005, 26.0025, "Poarta C", 3),
        Poi(Venue.EXIT, 44.0020, 26.0015, "Poarta nord", 4),
    )
    private val planA = EvacuationPlan(
        1, "Plan A", mapOf("a" to EvacuationExit(1, 2), "b" to EvacuationExit(2, 3), "c" to EvacuationExit(3, 4)),
    )
    private val planB = EvacuationPlan(2, "Plan B", mapOf("a" to EvacuationExit(4, 1), "b" to EvacuationExit(4), "c" to EvacuationExit(4, 3)))
    private val oneExit = EvacuationPlan(3, "O singură poartă", mapOf("a" to EvacuationExit(4), "b" to EvacuationExit(4), "c" to EvacuationExit(4)))
    private val missingZone = EvacuationPlan(4, "Fără C", mapOf("a" to EvacuationExit(1, 2), "b" to EvacuationExit(2, 1)))
    private val venue = Venue(
        "test", Bounds(43.99, 25.99, 44.01, 26.01), zones, meetingPoint = GeoPoint(44.003, 26.0015), pois = exits,
        evacuation = listOf(planA, planB, oneExit, missingZone),
    )

    private fun sign(topic: Int, level: Int, extras: NoticeExtras = NoticeExtras.NONE, zone: String = "", seq: Long = 1) =
        Notice.sign(testCrypto, credential, topic, level, seq, 0, 3600, 5, zone, extras = extras)

    private fun state(vararg notices: Notice) =
        MapState.of(NoticeBoard(notices.map { HeldNotice(it.topic, it.seq, it.encode().toHex(), 0) }), venue, 10)

    private fun closed(vararg ids: String) = sign(
        NoticeTopic.MAP_STATE, 1, NoticeExtras(states = zones.map { if (it.id in ids) ZoneState.CLOSED else ZoneState.OPEN }), seq = 1,
    )

    private fun evacuation(plan: Int?, zones: Set<String> = emptySet()) =
        sign(NoticeTopic.ALERT, NoticeLevel.EVACUATION, Evacuations.extras(venue, plan, zones)!!, seq = 2)

    @Test
    fun aZoneSetAlarmsTheZonesInItAndLeavesTheOthersQuiet() {
        val pause = sign(NoticeTopic.ALERT, NoticeLevel.PAUSE, NoticeExtras(ZoneSet.target(venue, setOf("a", "b"))))
        assertEquals(NoticeAlarm.LOUD, NoticeReach.alarm(pause, venue, "a", exercise = false))
        assertEquals(NoticeAlarm.LOUD, NoticeReach.alarm(pause, venue, "b", exercise = false))
        assertEquals(NoticeAlarm.NONE, NoticeReach.alarm(pause, venue, "c", exercise = false), "out of the set: in the list, quiet")
        assertEquals(NoticeAlarm.NONE, NoticeReach.alarm(pause, venue, "", exercise = false), "outside every zone")
        assertEquals(NoticeAlarm.LOUD, NoticeReach.alarm(pause, venue, null, exercise = false), "a phone that doesn't know may be in it")
        val info = sign(NoticeTopic.ALERT, NoticeLevel.INFO, NoticeExtras(ZoneSet.target(venue, setOf("a"))))
        assertEquals(NoticeAlarm.NONE, NoticeReach.alarm(info, venue, "a", exercise = false), "information never sounds")
        val oneZone = sign(NoticeTopic.ALERT, NoticeLevel.PAUSE, zone = "c")
        assertEquals(NoticeAlarm.LOUD, NoticeReach.alarm(oneZone, venue, "c", exercise = false))
        assertEquals(NoticeAlarm.NONE, NoticeReach.alarm(oneZone, venue, "a", exercise = false))
    }

    @Test
    fun anExerciseTakesTheScreenWithoutTheAlarmSound() {
        val evac = evacuation(1, setOf("a"))
        assertEquals(NoticeAlarm.SILENT, NoticeReach.alarm(evac, venue, "a", exercise = true))
        assertEquals(NoticeAlarm.NONE, NoticeReach.alarm(evac, venue, "b", exercise = true))
        assertEquals(NoticeAlarm.LOUD, NoticeReach.alarm(evac, venue, "a", exercise = false))
    }

    @Test
    fun noZonesOrEveryZoneIsTheWholeSiteAndAddsNoBytes() {
        assertNull(ZoneSet.target(venue, emptySet()))
        assertNull(ZoneSet.target(venue, setOf("a", "b", "c")))
        assertNull(ZoneSet.target(venue, setOf("nowhere")))
        assertEquals(ZoneSet(3, setOf(0, 2)), ZoneSet.target(venue, setOf("a", "c")))
        assertEquals(NoticeExtras.NONE, Evacuations.extras(venue, null, emptySet()))
        assertEquals(0, Evacuations.extras(venue, null, setOf("a", "b", "c"))!!.encode().size)
    }

    @Test
    fun eachZoneTakesItsExitInEachPlan() {
        val expected = mapOf(
            1 to mapOf("a" to (1 to 2), "b" to (2 to 3), "c" to (3 to 4)),
            2 to mapOf("a" to (4 to 1), "b" to (4 to null), "c" to (4 to 3)),
        )
        for ((plan, byZone) in expected) {
            val state = state(evacuation(plan))
            for ((zone, pair) in byZone) {
                val target = Evacuations.target(venue, state, zone)!!
                assertEquals(plan, target.plan.id)
                assertEquals(pair.first, target.exit.number, "plan $plan, zone $zone")
                assertEquals(pair.second, target.fallback?.number, "plan $plan, zone $zone")
                assertFalse(target.switched)
                assertEquals(target.exit.point, target.point)
            }
        }
    }

    @Test
    fun aClosedMainExitSendsTheZoneToItsAlternative() {
        // zone b's main exit, Poarta B, stands in b; with b closed, b and a leave by their alternatives
        val state = state(closed("b"), evacuation(1, setOf("a", "b")))
        val b = Evacuations.target(venue, state, "b")!!
        assertEquals(3, b.exit.number)
        assertTrue(b.switched)
        assertNull(b.fallback, "the closed one is offered to nobody")
        val a = Evacuations.target(venue, state, "a")!!
        assertEquals(1, a.exit.number, "a's main exit is open")
        assertNull(a.fallback, "its alternative, Poarta B, is closed")
        assertNull(Evacuations.target(venue, state, "c"), "c isn't evacuated")
        // both closed: the plan's main exit stands
        val both = Evacuations.target(venue, state(closed("b", "c"), evacuation(1)), "b")!!
        assertEquals(2, both.exit.number)
        assertFalse(both.switched)
        assertTrue(both.closed, "the screen warns that the exit stands in a closed zone")
        assertFalse(b.closed)
        assertFalse(a.closed)
    }

    @Test
    fun aPlanThatFailsTheChecksCannotBeStarted() {
        assertNull(Evacuations.extras(venue, oneExit.id, emptySet()), "one exit for every zone")
        assertNull(Evacuations.extras(venue, missingZone.id, emptySet()), "a zone without an exit")
        assertNull(Evacuations.extras(venue, 99, emptySet()), "a plan the venue doesn't have")
        assertEquals(NoticeExtras(ZoneSet(3, setOf(1)), plan = 2), Evacuations.extras(venue, 2, setOf("b")))
    }

    @Test
    fun withoutAPositionThePhoneGetsThePlansExits() {
        val state = state(evacuation(2))
        assertNull(Evacuations.target(venue, state, null))
        assertNull(Evacuations.target(venue, state, ""))
        assertEquals(listOf(1, 3, 4), Evacuations.exits(venue, Evacuations.plan(venue, state)).map { it.exit.number })
        assertEquals(listOf(1, 2, 3, 4), Evacuations.exits(venue, null).map { it.exit.number }, "no plan: every exit")
    }

    @Test
    fun theListOfExitsPutsTheClosedOnesLastAndTheNearestFirst() {
        val state = state(closed("a"), evacuation(null))
        val byNumber = Evacuations.exits(venue, null, state)
        assertEquals(listOf(2, 3, 4, 1), byNumber.map { it.exit.number }, "Poarta A stands in closed a")
        assertEquals(listOf(false, false, false, true), byNumber.map { it.closed })
        val fromC = Evacuations.exits(venue, null, state, from = GeoPoint(44.0005, 26.0026))
        assertEquals(listOf(3, 2, 4, 1), fromC.map { it.exit.number })
    }

    @Test
    fun aNoticeFromBeforeTheExtensionsBehavesAsBefore() {
        val old = Notice.sign(testCrypto, credential, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 1, 0, 3600, 5)
        assertEquals(0, old.flags and Notice.EXTRAS)
        assertEquals(0, old.extensions.size)
        val decoded = Notice.decode(old.encode())!!
        assertEquals(NoticeExtras.NONE, decoded.extras)
        for (zone in listOf("a", "b", "c", "", null)) assertEquals(NoticeAlarm.LOUD, NoticeReach.alarm(decoded, venue, zone, exercise = false))
        val state = state(decoded)
        assertEquals(Evacuation(null, emptySet()), state.evacuation)
        assertNull(Evacuations.target(venue, state, "a"), "no plan named: the text and the exits, as before")
        assertEquals(4, Evacuations.exits(venue, null).size)
    }

    @Test
    fun thePhonesZoneComesFromItsFixOrTheZonePickedByHand() {
        val now = 1_000_000L
        val inB = GeoFix(44.0005, 26.0015, 5f, now - 1_000)
        assertEquals("b", NoticeReach.zone(venue, Settings(), inB, now))
        val pickedA = Settings(manualZone = "a", manualZoneAt = now - 60_000)
        assertEquals("b", NoticeReach.zone(venue, pickedA, inB, now), "the fix wins")
        assertEquals("a", NoticeReach.zone(venue, pickedA, inB.copy(timeMs = now - GeoFix.FRESH_MS), now), "a stale fix doesn't")
        val longAgo = pickedA.copy(manualZoneAt = now - NoticeReach.MANUAL_ZONE_MS - 1)
        assertNull(NoticeReach.zone(venue, longAgo, null, now), "a zone picked long ago is unknown")
        assertNull(NoticeReach.zone(venue, Settings(manualZone = "a"), null, now), "as is one picked before the time was kept")
        assertEquals("", NoticeReach.zone(venue, Settings(), GeoFix(44.005, 26.005, 5f, now), now), "outside every zone")
        assertNull(NoticeReach.zone(venue, Settings(), null, now))
        assertEquals("c", NoticeReach.zone(venue, Settings(simLat = 44.0005, simLon = 26.0025), null, now), "the demo's position")
    }

    @Test
    fun aStaleZonePickedByHandDoesNotSilenceAnEvacuation() {
        val now = 1_000_000L
        val evac = evacuation(1, setOf("b"))
        val fresh = Settings(manualZone = "a", manualZoneAt = now - 5 * 60_000)
        assertEquals(NoticeAlarm.NONE, NoticeReach.alarm(evac, venue, NoticeReach.zone(venue, fresh, null, now), exercise = false))
        val stale = fresh.copy(manualZoneAt = now - 30 * 60_000)
        assertEquals(NoticeAlarm.LOUD, NoticeReach.alarm(evac, venue, NoticeReach.zone(venue, stale, null, now), exercise = false))
    }

    @Test
    fun aPhoneWhoseFixReachesAnEvacuatedZoneIsConcerned() {
        val now = 1_000_000L
        // in a, about 20 m west of b's border
        val nearB = GeoFix(44.0005, 26.00073, 30f, now)
        val here = NoticeReach.whereabouts(venue, Settings(), nearB, now)
        assertEquals("a", here.zone)
        assertEquals(setOf("b"), here.near)
        val evac = evacuation(1, setOf("b"))
        assertEquals(NoticeAlarm.LOUD, NoticeReach.alarmAt(evac, venue, here, exercise = false))
        assertEquals("b", Evacuations.zoneFor(state(evac), here), "it follows b's way out")
        val precise = NoticeReach.whereabouts(venue, Settings(), nearB.copy(accuracyM = 5f), now)
        assertTrue(precise.near.isEmpty())
        assertEquals(NoticeAlarm.NONE, NoticeReach.alarmAt(evac, venue, precise, exercise = false))
        assertTrue(NoticeReach.whereabouts(venue, Settings(simLat = nearB.lat, simLon = nearB.lon), null, now).near.isEmpty(), "the demo's position is exact")
    }

    @Test
    fun anEvacuationComesThroughWithTheNoticesTurnedOff() {
        val evac = evacuation(1, setOf("a"))
        val heat = sign(NoticeTopic.HEAT, 3)
        val pause = sign(NoticeTopic.ALERT, NoticeLevel.PAUSE)
        fun posts(n: Notice, zone: String?, exercise: Boolean = false, notices: Boolean = false) =
            NoticeReach.alarm(n, venue, zone, exercise).takeIf { NoticeReach.posts(n, it, notices, showsEvacuation = true) }
        assertEquals(NoticeAlarm.LOUD, posts(evac, "a"))
        assertNull(posts(evac, "b"), "another zone's evacuation stays behind the opt-in")
        assertEquals(NoticeAlarm.NONE, posts(evac, "b", notices = true), "with the notices on, it shows quietly")
        assertEquals(NoticeAlarm.SILENT, posts(evac, "a", exercise = true))
        assertNull(posts(pause, "a"), "a pause stays behind the opt-in")
        assertNull(posts(heat, "a"))
        val cancel = Notice.sign(testCrypto, credential, NoticeTopic.ALERT, NoticeLevel.EVACUATION, 3, 0, 3600, 0, flags = Notice.CANCEL)
        assertEquals(NoticeAlarm.NONE, posts(cancel, "b"), "the withdrawal passes, so the notification goes")
        assertFalse(NoticeReach.posts(evac, NoticeAlarm.LOUD, showsNotices = false, showsEvacuation = false), "the console")
    }

    @Test
    fun onlyTheMapStateIsSilent() {
        val map = Notice.sign(testCrypto, credential, NoticeTopic.MAP_STATE, 1, 1, 0, 3600, 0, extras = NoticeExtras(states = zones.map { ZoneState.OPEN }))
        assertTrue(map.silent)
        assertFalse(Notice.sign(testCrypto, credential, NoticeTopic.HEAT, 1, 1, 0, 3600, 0).silent, "another topic with no words still shows")
        assertFalse(Notice.sign(testCrypto, credential, NoticeTopic.ALERT, NoticeLevel.INFO, 1, 0, 3600, 0).silent)
    }
}
