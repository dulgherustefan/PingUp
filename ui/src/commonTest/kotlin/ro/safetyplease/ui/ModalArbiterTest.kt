package ro.safetyplease.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModalArbiterTest {
    private val now = 1_000_000L
    private val evacuation = ModalAsk(ModalKind.EVACUATION, "evacuation:1")
    private val manDown = ModalAsk(ModalKind.MAN_DOWN, "mandown", untilMs = now + 30_000)
    private val responder = ModalAsk(ModalKind.RESPONDER, "responder:a", title = "Inconștient sau nu respiră")
    private val notice = ModalAsk(ModalKind.NOTICE, "notice:1")
    private val lost = ModalAsk(ModalKind.FRIEND, "lost:7", untilMs = now + 60_000)
    private val come = ModalAsk(ModalKind.MEETUP, "come:7")

    private fun pick(vararg asks: ModalAsk, acted: Set<String> = emptySet(), at: Long = now, walkFrom: ModalKind? = null, reopened: String? = null) =
        ModalOrder.pick(asks.toList(), acted, at, walkFrom, reopened)

    @Test
    fun onlyTheHighestOpens() {
        assertEquals(responder, pick(come, lost, responder, notice).dialog)
        assertEquals(notice, pick(come, lost, notice).dialog)
        assertEquals(lost, pick(come, lost).dialog)
        assertEquals(come, pick(come).dialog)
        assertNull(pick().dialog)
        assertEquals(evacuation, pick(responder, evacuation).dialog)
        assertEquals(manDown, pick(evacuation, manDown).dialog, "the countdown goes before the evacuation's screen")
    }

    @Test
    fun lowerOnesWaitAndOpenWhenTheHigherCloses() {
        val first = pick(lost, responder)
        assertEquals(ModalShow.DIALOG, first.show(responder.key))
        assertEquals(ModalShow.WAIT, first.show(lost.key))
        assertEquals(lost, pick(lost).dialog, "the friend's dialog opens once the alert is gone")
    }

    @Test
    fun anExpiredAskIsDropped() {
        assertEquals(come, pick(lost, come, at = now + 60_000).dialog)
        assertNull(pick(manDown, at = now + 30_000).dialog)
    }

    @Test
    fun anAnsweredDialogDoesNotOpenAgain() {
        assertEquals(come, pick(lost, come, acted = setOf(lost.key)).dialog)
    }

    @Test
    fun overAWalkOnlyHigherAlertsShowAsABanner() {
        // "Vin acolo" opened the walk while the responder's alert was still open
        val walking = pick(lost, responder, come, acted = setOf(lost.key), walkFrom = ModalKind.FRIEND)
        assertNull(walking.dialog, "nothing opens over the route")
        assertEquals(responder, walking.banner)
        assertEquals(ModalShow.BANNER, walking.show(responder.key))
        assertEquals(ModalShow.WAIT, walking.show(come.key))
        assertEquals(responder, pick(responder, walkFrom = ModalKind.FRIEND, reopened = responder.key).dialog, "a tap on the banner opens it")
        // the way to the defibrillator: the friends wait for it to end
        val toDefibrillator = pick(lost, come, walkFrom = ModalKind.RESPONDER)
        assertNull(toDefibrillator.dialog)
        assertNull(toDefibrillator.banner)
        assertEquals(manDown, pick(manDown, lost, walkFrom = ModalKind.RESPONDER).dialog, "the whole screen still takes the route")
    }

    @Test
    fun anEvacuationHoldsFriendsEvenMinimised() {
        assertEquals(responder, ModalOrder.pick(listOf(lost, responder), emptySet(), now, evacuating = true).dialog)
        assertNull(ModalOrder.pick(listOf(lost, come), emptySet(), now, evacuating = true).dialog)
    }

    @Test
    fun theArbiterKeepsTheAnswerUntilItsSourceDropsIt() {
        var walk = false
        val modals = ModalArbiter({ now }) { walk }
        modals.offer(ModalKind.FRIEND, lost)
        modals.offer(ModalKind.RESPONDER, responder)
        assertEquals(responder, modals.pick.dialog)
        modals.offer(ModalKind.RESPONDER, null)
        assertEquals(lost, modals.pick.dialog)

        // "Vin acolo": the walk opens while the alert still sits in its list
        modals.acted(lost.key, walks = true)
        walk = true
        assertNull(modals.pick.dialog)
        modals.offer(ModalKind.RESPONDER, responder)
        assertEquals(responder, modals.pick.banner)
        modals.reopen(responder.key)
        assertEquals(responder, modals.pick.dialog)
        modals.acted(responder.key)
        assertNull(modals.pick.dialog)
        assertNull(modals.pick.banner)

        modals.offer(ModalKind.RESPONDER, null)
        modals.offer(ModalKind.FRIEND, null)
        walk = false
        modals.walkEnded()
        modals.offer(ModalKind.FRIEND, lost)
        assertEquals(lost, modals.pick.dialog, "a key the source dropped and offers again is new")
    }

    @Test
    fun aFriendLinkWaitsBehindWhatIsAboveFriends() {
        val modals = ModalArbiter({ now }) { false }
        assertTrue(modals.admits(OpenTarget.CHECKIN_ALERTS))

        modals.evacuating = true
        assertTrue(modals.holdsFriends)
        assertFalse(modals.admits(OpenTarget.CHECKIN_ALERTS), "a missed check-in waits")
        // the rest goes on
        assertTrue(modals.admits(OpenTarget.INCIDENTS))
        assertTrue(modals.admits(OpenTarget.REPORT_PREFIX + "abc"))
        modals.evacuating = false
        assertFalse(modals.holdsFriends)
        assertEquals(OpenTarget.CHECKIN_ALERTS, modals.release())
        assertNull(modals.release(), "the held link opens once")

        modals.offer(ModalKind.RESPONDER, responder)
        assertFalse(modals.admits(OpenTarget.CHECKIN_ALERTS), "behind the responder's alert too")
        modals.offer(ModalKind.RESPONDER, null)
        assertEquals(OpenTarget.CHECKIN_ALERTS, modals.release())
        modals.offer(ModalKind.MEETUP, come)
        assertTrue(modals.admits(OpenTarget.CHECKIN_ALERTS), "not behind a friend's request")
    }
}
