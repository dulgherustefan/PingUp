package ro.safetyplease.core.announce

import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.crypto.Certificate
import ro.safetyplease.core.crypto.Credential
import ro.safetyplease.core.crypto.testCrypto
import ro.safetyplease.core.util.hexToBytes
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The map state and the zone sets of the console's notices, and what a phone makes of them. */
class MapStateTest {
    private val control = testCrypto.signKeyPair()
    private val credential = Credential(
        Certificate.issue(testCrypto, CertRole.CONTROL, control.publicKey, 0, 0xFFFFFFFFL, "Control", testCrypto.signKeyPair()), control,
    )
    private val venue = Venue(
        "test", Bounds(44.0, 26.0, 44.01, 26.01),
        listOf("a", "b", "c", "d", "e").map { Zone(it, it, "#336699", listOf(GeoPoint(44.0, 26.0), GeoPoint(44.0, 26.001), GeoPoint(44.001, 26.0))) },
    )

    private fun notice(topic: Int, level: Int, seq: Long = 1, extras: NoticeExtras = NoticeExtras.NONE, zone: String = "", until: Long = 3600, template: Int = 0) =
        Notice.sign(testCrypto, credential, topic, level, seq, 0, until, template, zone, extras = extras)

    private fun board(vararg notices: Notice) =
        NoticeBoard(notices.map { HeldNotice(it.topic, it.seq, it.encode().toHex(), 0) })

    @Test
    fun extrasComeBackAsWritten() {
        val extras = NoticeExtras(ZoneSet(10, setOf(0, 7, 9)), 200, ZoneState.entries + ZoneState.OPEN)
        assertEquals(extras, NoticeExtras.decode(extras.encode()))
        assertEquals(0, NoticeExtras.NONE.encode().size, "no extras, no bytes: the notice keeps its 1.1 layout")
    }

    @Test
    fun aReaderSkipsWhatItDoesNotKnowAndIgnoresWhatItCannotRead() {
        // an unknown type, a plan of 0, a zone set shorter than its count, then a good plan
        assertEquals(NoticeExtras(plan = 4), NoticeExtras.decode("7e03010203".hexToBytes() + "020100".hexToBytes() + "01020980".hexToBytes() + "020104".hexToBytes()))
        assertEquals(NoticeExtras.NONE, NoticeExtras.decode("0105".hexToBytes()), "runs past the end")
    }

    @Test
    fun theLatestMapStateGivesEveryZoneItsState() {
        val states = listOf(ZoneState.OPEN, ZoneState.CROWDED, ZoneState.CLOSED, ZoneState.EXIT_ONLY, ZoneState.OPEN)
        val state = MapState.of(board(notice(NoticeTopic.MAP_STATE, 1, extras = NoticeExtras(states = states))), venue, 10)
        assertEquals(mapOf("b" to ZoneState.CROWDED, "c" to ZoneState.CLOSED, "d" to ZoneState.EXIT_ONLY), state.zones)
        assertEquals(ZoneState.OPEN, state.state("a"))
        assertNull(state.evacuation)
    }

    @Test
    fun aMapStateForAnotherZoneListOrExpiredOrWithdrawnLeavesTheMapOpen() {
        val four = notice(NoticeTopic.MAP_STATE, 1, extras = NoticeExtras(states = List(4) { ZoneState.CLOSED }))
        assertEquals(MapState.OPEN, MapState.of(board(four), venue, 10))
        val five = notice(NoticeTopic.MAP_STATE, 1, extras = NoticeExtras(states = List(5) { ZoneState.CLOSED }), until = 60)
        assertEquals(MapState.OPEN, MapState.of(board(five), venue, 61))
        val withdrawn = Notice.sign(testCrypto, credential, NoticeTopic.MAP_STATE, 1, 2, 30, 3600, 0, flags = Notice.CANCEL)
        assertEquals(MapState.OPEN, MapState.of(board(withdrawn), venue, 40))
    }

    @Test
    fun anEvacuationNamesItsPlanAndItsZones() {
        val targeted = notice(NoticeTopic.ALERT, NoticeLevel.EVACUATION, extras = NoticeExtras(ZoneSet.of(venue, listOf("b", "e")), plan = 2), template = 5)
        assertEquals(Evacuation(2, setOf("b", "e")), MapState.of(board(targeted), venue, 10).evacuation)
        val everyone = notice(NoticeTopic.ALERT, NoticeLevel.EVACUATION, template = 5)
        val all = MapState.of(board(everyone), venue, 10).evacuation!!
        assertEquals(Evacuation(null, emptySet()), all)
        assertTrue(all.covers("a"))
        val oneZone = notice(NoticeTopic.ALERT, NoticeLevel.EVACUATION, zone = "c", template = 5)
        assertEquals(setOf("c"), MapState.of(board(oneZone), venue, 10).evacuation!!.zones)
        assertNull(MapState.of(board(notice(NoticeTopic.ALERT, NoticeLevel.PAUSE, template = 3)), venue, 10).evacuation, "a pause is no evacuation")
    }

    @Test
    fun aZoneSetDecidesWhoANoticeIsFor() {
        val set = notice(NoticeTopic.ALERT, NoticeLevel.INFO, extras = NoticeExtras(ZoneSet.of(venue, listOf("a"))), template = 1)
        assertTrue(set.concerns(venue, "a"))
        assertFalse(set.concerns(venue, "b"))
        assertFalse(set.concerns(venue, null), "a phone that doesn't know its zone isn't in the set")
        val otherList = notice(NoticeTopic.ALERT, NoticeLevel.INFO, extras = NoticeExtras(ZoneSet(3, setOf(0))), template = 1)
        assertTrue(otherList.concerns(venue, "b"), "made for another venue: for everyone")
        assertTrue(notice(NoticeTopic.ALERT, NoticeLevel.INFO, template = 1).concerns(venue, null))
    }

    @Test
    fun aMapStateHasNoWordsAndIsNeverShown() {
        assertTrue(notice(NoticeTopic.MAP_STATE, 1, extras = NoticeExtras(states = List(5) { ZoneState.OPEN })).silent)
        assertFalse(notice(NoticeTopic.ALERT, NoticeLevel.INFO, template = 1).silent)
        assertFalse(Notice.sign(testCrypto, credential, NoticeTopic.ALERT, 1, 2, 0, 60, 0, flags = Notice.CANCEL).silent, "a cancel withdraws")
    }
}
