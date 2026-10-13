package ro.safetyplease.ui.notices

import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.core.announce.HeldNotice
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeBoard
import ro.safetyplease.core.announce.NoticeExtras
import ro.safetyplease.core.announce.NoticeLevel
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneSet
import ro.safetyplease.core.announce.Whereabouts
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.EvacuationExit
import ro.safetyplease.core.venue.EvacuationPlan
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.ui.map.PinKind
import ro.safetyplease.ui.map.ZoneStyle
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_body_plan
import ro.safetyplease.ui.resources.control_body_zones
import ro.safetyplease.ui.resources.control_map_all_open
import ro.safetyplease.ui.resources.control_map_closed_list
import ro.safetyplease.ui.resources.control_map_crowded_list
import ro.safetyplease.ui.resources.control_map_exit_list
import ro.safetyplease.ui.resources.evac_exits
import ro.safetyplease.ui.resources.evac_exits_all
import ro.safetyplease.ui.resources.evac_no_exit
import ro.safetyplease.ui.resources.evac_outside
import ro.safetyplease.ui.resources.evac_unknown
import ro.safetyplease.ui.resources.notice_in_zone
import ro.safetyplease.ui.text.ControlNoticeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The console's map as the phones draw and word it, and which notice takes the screen of which phone. */
class ControlLayerTest {
    private fun square(west: Double) =
        listOf(GeoPoint(44.000, west), GeoPoint(44.000, west + 0.001), GeoPoint(44.001, west + 0.001), GeoPoint(44.001, west))

    private val venue = Venue(
        "test", Bounds(43.99, 25.99, 44.01, 26.01),
        listOf("a", "b", "c").mapIndexed { i, id -> Zone(id, id.uppercase(), "#336699", square(26.000 + i * 0.001)) },
        pois = listOf(Poi(Venue.EXIT, 44.0005, 26.0005, "Poarta A", 1), Poi(Venue.EXIT, 44.0005, 26.0025, "", 3)),
        evacuation = listOf(
            EvacuationPlan(1, "Plan A", mapOf("a" to EvacuationExit(1, 3), "b" to EvacuationExit(3, 1), "c" to EvacuationExit(3, 1))),
            EvacuationPlan(2, "Fără C", mapOf("a" to EvacuationExit(1, 3), "b" to EvacuationExit(3, 1))),
        ),
    )
    private val certificate = Certificate(CertRole.CONTROL, ByteArray(32), 0, 0xFFFFFFFFL, 1, "Control", ByteArray(64))

    private fun notice(topic: Int, level: Int, extras: NoticeExtras = NoticeExtras.NONE, seq: Long = 1, zone: String = ""): Notice {
        val bytes = extras.encode()
        return Notice(
            topic, level, if (bytes.isEmpty()) 0 else Notice.EXTRAS, seq, 0, 3600, 5, zone, emptyList(), certificate, ByteArray(64), bytes,
        )
    }

    private fun mapState(vararg states: ZoneState) = notice(NoticeTopic.MAP_STATE, 1, NoticeExtras(states = states.toList()))

    private fun evacuation(zones: Set<String>, plan: Int? = 1) = notice(
        NoticeTopic.ALERT, NoticeLevel.EVACUATION, NoticeExtras(ZoneSet.of(venue, zones).takeIf { zones.isNotEmpty() }, plan), seq = 2,
    )

    private fun state(vararg notices: Notice) =
        MapState.of(NoticeBoard(notices.map { HeldNotice(it.topic, it.seq, it.encode().toHex(), 0) }), venue, 10)

    private fun shown(n: Notice, alerted: Boolean = false) = ShownNotice(HeldNotice(n.topic, n.seq, "", 0, alerted = alerted), n, "", "")

    private val labels = ControlLabels("Închis", "Doar ieșire", "Ieșirea ta", "Ieșirea %1\$d")

    /** Fills a text as "key(arg|arg)", so the test reads which text went where. */
    private val format = { res: StringResource, args: Array<String> -> "${res.key}(${args.joinToString("|")})" }

    @Test
    fun theLayerStylesTheZonesAndNamesTheClosedOnes() {
        val state = state(mapState(ZoneState.CLOSED, ZoneState.CROWDED, ZoneState.EXIT_ONLY))
        val overlay = controlOverlay(venue, state, null, labels)
        assertEquals(mapOf("a" to ZoneStyle.CLOSED, "b" to ZoneStyle.CROWDED, "c" to ZoneStyle.EXIT_ONLY), overlay.zones)
        assertEquals(listOf("A: Închis", "C: Doar ieșire"), overlay.pins.map { it.label })
        assertTrue(overlay.pins.all { it.kind == PinKind.SERVICE })
    }

    @Test
    fun anEvacuationHighlightsItsZonesAndPinsThisPhonesExit() {
        val state = state(evacuation(setOf("b", "c")))
        val view = evacuationView(venue, state, "b")
        val overlay = controlOverlay(venue, state, view.target, labels)
        assertEquals(mapOf("b" to ZoneStyle.EVACUATE, "c" to ZoneStyle.EVACUATE), overlay.zones)
        val pin = overlay.pins.single()
        assertEquals(PinKind.SEARCH_RESULT, pin.kind)
        assertEquals("Ieșirea ta: Ieșirea 3", pin.label, "an exit without a name goes by its number")
        assertEquals(GeoPoint(44.0005, 26.0025), pin.point)
    }

    @Test
    fun withoutAZoneTheEvacuationListsThePlansExits() {
        val state = state(evacuation(emptySet()))
        val unknown = evacuationView(venue, state, null)
        assertNull(unknown.target)
        assertEquals(EvacuationCase.UNKNOWN, unknown.case)
        assertEquals(Res.string.evac_unknown, caseText(unknown.case))
        assertEquals(listOf(1, 3), unknown.exits.map { it.exit.number })
        assertEquals(Res.string.evac_exits, exitsHeading(unknown), "the plan's exits, as listed")
        val outside = evacuationView(venue, state, "")
        assertEquals(EvacuationCase.OUTSIDE, outside.case)
        assertEquals(Res.string.evac_outside, caseText(outside.case))
        val inA = evacuationView(venue, state, "a")
        assertEquals("A", inA.zone?.name)
        assertEquals(EvacuationCase.EXIT, inA.case)
    }

    @Test
    fun aKnownZoneWithoutAnExitOfItsOwnGoesToTheExitsListed() {
        val planless = evacuationView(venue, state(evacuation(emptySet(), plan = null)), "a")
        assertNull(planless.target)
        assertEquals(EvacuationCase.NO_EXIT, planless.case)
        assertEquals(Res.string.evac_no_exit, caseText(planless.case))
        assertEquals(Res.string.evac_exits_all, exitsHeading(planless), "no plan: the venue's exits, under their own heading")
        assertEquals(listOf(1, 3), planless.exits.map { it.exit.number })
        val lacking = evacuationView(venue, state(evacuation(emptySet(), plan = 2)), "c")
        assertEquals(EvacuationCase.NO_EXIT, lacking.case)
        assertEquals(Res.string.evac_exits, exitsHeading(lacking))
    }

    @Test
    fun aWithdrawnOrExpiredEvacuationLeavesTheMapAndTheScreen() {
        val evac = evacuation(setOf("b"))
        val cancel = Notice(NoticeTopic.ALERT, NoticeLevel.EVACUATION, Notice.CANCEL, 3, 0, 3600, 0, "", emptyList(), certificate, ByteArray(64))
        val held = NoticeBoard(listOf(HeldNotice(evac.topic, evac.seq, evac.encode().toHex(), 0)))
        assertTrue(MapState.of(held, venue, 10).evacuation != null)
        val withdrawn = NoticeBoard(listOf(HeldNotice(cancel.topic, cancel.seq, cancel.encode().toHex(), 0)))
        for ((board, now) in listOf(withdrawn to 10L, held to 3600L)) {
            assertNull(MapState.of(board, venue, now).evacuation)
            val shown = board.active(now).map { shown(it.second) }
            assertNull(myEvacuation(shown, venue, Whereabouts("b")))
        }
    }

    @Test
    fun anEvacuationTakesTheScreenOnlyInItsZonesAndAPauseOnlyOnce() {
        val evac = shown(evacuation(setOf("b")))
        val pause = shown(notice(NoticeTopic.ALERT, NoticeLevel.PAUSE, zone = "a"))
        val all = listOf(evac, pause)
        assertSame(evac, myEvacuation(all, venue, Whereabouts("b")))
        assertNull(myEvacuation(all, venue, Whereabouts("a")), "another zone's evacuation stays a band")
        assertSame(evac, myEvacuation(all, venue, Whereabouts("a", near = setOf("b"))), "unless the fix reaches its zone")
        assertSame(evac, myEvacuation(all, venue, Whereabouts(null)), "a phone that doesn't know its zone may be in it")
        assertSame(pause, screenAlert(all, venue, Whereabouts("a")), "an evacuation never takes the pause's way")
        assertNull(screenAlert(all, venue, Whereabouts("c")))
        assertNull(screenAlert(listOf(shown(pause.notice, alerted = true)), venue, Whereabouts("a")), "once read")
    }

    @Test
    fun theMapStateAndTheTargetsReadAsWords() {
        val map = mapState(ZoneState.CLOSED, ZoneState.CROWDED, ZoneState.CLOSED)
        assertEquals(
            "${Res.string.control_map_closed_list.key}(A, C); ${Res.string.control_map_crowded_list.key}(B)",
            ControlNoticeText.body(map, venue, "", format),
        )
        assertEquals(
            "${Res.string.control_map_all_open.key}()",
            ControlNoticeText.body(mapState(ZoneState.OPEN, ZoneState.OPEN, ZoneState.OPEN), venue, "", format),
        )
        assertEquals(
            "${Res.string.control_map_exit_list.key}(B)",
            ControlNoticeText.body(mapState(ZoneState.OPEN, ZoneState.EXIT_ONLY, ZoneState.OPEN), venue, "", format),
        )
        assertEquals(
            "${Res.string.control_body_plan.key}(${Res.string.control_body_zones.key}(B, C|Ieși)|Plan A)",
            ControlNoticeText.body(evacuation(setOf("b", "c")), venue, "Ieși", format),
        )
        assertEquals(
            "${Res.string.control_body_plan.key}(${Res.string.notice_in_zone.key}(A|Ieși)|Plan A)",
            ControlNoticeText.body(notice(NoticeTopic.ALERT, NoticeLevel.EVACUATION, NoticeExtras(plan = 1), zone = "a"), venue, "Ieși", format),
        )
        assertNull(ControlNoticeText.body(notice(NoticeTopic.ALERT, NoticeLevel.INFO), venue, "Text", format), "a notice as before")
        assertEquals("Ieșirea 3 e aici", ControlNoticeText.fill("%1\$s e aici", arrayOf("Ieșirea 3")))
    }
}
