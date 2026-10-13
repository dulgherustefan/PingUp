package ro.safetyplease.ui.safety

import androidx.compose.runtime.saveable.SaverScope
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.ReportExtras
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.OpenTarget
import ro.safetyplease.ui.Tab
import ro.safetyplease.ui.landing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyUiTest {
    @Test
    fun theCountdownRoundsUpAndNeverGoesNegative() {
        assertEquals("30:00", countdownText(30 * 60_000L))
        assertEquals("9:05", countdownText(9 * 60_000L + 4_200L))
        assertEquals("0:01", countdownText(1))
        assertEquals("0:00", countdownText(0))
        assertEquals("0:00", countdownText(-5_000))
        assertEquals("1:02:03", countdownText(3_723_000L))
        assertEquals("12:00:00", countdownText(12 * 3_600_000L))
    }

    @Test
    fun chosenFriendsSurviveAsTextAndStopAtFive() {
        assertEquals(listOf(3L, 7L), chosenIds(savedIds(listOf(3, 7))))
        assertEquals(emptyList(), chosenIds(""))
        assertEquals(listOf(1L, 2L), chosenIds("1,x,2,"))
        assertEquals(listOf(1L, 2L), toggled(listOf(1, 2, 3), 3, 5))
        assertEquals(listOf(1L, 2L, 3L), toggled(listOf(1, 2), 3, 5))
        val full = listOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(full, toggled(full, 6, 5), "a sixth is not added")
    }

    @Test
    fun theOptionsOfAReportFollowItsCategory() {
        val state = ReportOptionsState(later = 120, noReply = true, witness = true)
        assertEquals(ReportExtras(happenedMinAgo = 120, anonymous = true, witness = true), state.extras(IncidentCategory.HARASSMENT))
        // left over from another category, they go nowhere
        assertEquals(ReportExtras(witness = true), state.extras(IncidentCategory.MEDICAL))
        assertEquals(ReportExtras.NONE, state.extras(IncidentCategory.LOST_FRIEND), "a lost friend is always about oneself")
        assertEquals(ReportExtras.NONE, state.extras(0))
        state.reset()
        assertEquals(ReportExtras.NONE, state.extras(IncidentCategory.HARASSMENT))
    }

    @Test
    fun whoCanReportLaterOrWithoutAReply() {
        assertTrue(offersLaterAndNoReply(IncidentCategory.HARASSMENT))
        assertTrue(offersLaterAndNoReply(IncidentCategory.VIOLENCE))
        assertFalse(offersLaterAndNoReply(IncidentCategory.MEDICAL), "a person who needs help now is not helped by a report that can't be answered")
        assertFalse(offersLaterAndNoReply(IncidentCategory.FIRE))
        assertFalse(offersLaterAndNoReply(IncidentCategory.LOST_CHILD))
        assertTrue(offersWitness(IncidentCategory.MEDICAL))
        assertFalse(offersWitness(IncidentCategory.LOST_FRIEND))
    }

    @Test
    fun theOptionsSurviveTheTurnOfThePhone() {
        val saved = ReportOptionsState.Saver
        val state = ReportOptionsState(360, noReply = false, witness = true)
        val kept = with(saved) { SaverScope { true }.save(state) }!!
        val back = saved.restore(kept)!!
        assertEquals(360, back.laterMin)
        assertFalse(back.noReply)
        assertTrue(back.witness)
    }

    @Test
    fun notificationsOpenTheCheckinAndTheFriendsAlerts() {
        assertEquals(Tab.REPORT to Dest.Safety(SafetyScreen.CHECKIN), landing(OpenTarget.CHECKIN))
        assertEquals(Tab.REPORT to Dest.Safety(SafetyScreen.FRIEND_ALERTS), landing(OpenTarget.CHECKIN_ALERTS))
    }
}
