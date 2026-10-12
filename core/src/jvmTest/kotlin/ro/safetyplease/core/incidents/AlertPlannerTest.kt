package ro.safetyplease.core.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.AlertPlanner.Companion.COOLDOWN_MS
import ro.safetyplease.core.incidents.AlertPlanner.Companion.MUTE_MS
import ro.safetyplease.core.incidents.AlertPlanner.Companion.REPEAT_MS
import ro.safetyplease.core.incidents.Routing.WIDEN_AFTER_MS
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity

class AlertPlannerTest {
    private val console = StaffProfile(console = true, team = "Control")
    private val medic = StaffProfile(console = false, team = "Medical 2", zone = "camping")
    private val start = 1_000_000L

    private fun incident(
        id: String = "a",
        severity: Int = Severity.URGENT,
        zone: String = "main-stage",
        status: Int = AckStatus.RECEIVED,
        team: String = "",
        assigned: Boolean = false,
        receivedAt: Long = start,
        statusAt: Long = start,
        epoch: Int = 0,
    ) = StaffIncident(
        incidentId = id, category = IncidentCategory.MEDICAL, severity = severity, zone = zone, reportedAt = receivedAt,
        receivedAt = receivedAt, hops = 1, status = status, teamName = team, assigned = assigned, statusAt = statusAt, epoch = epoch,
    )

    @Test
    fun theConsoleRingsOnFullScreenOnceThenAgainUntilSomeoneHasIt() {
        val planner = AlertPlanner(start)
        val urgent = incident()
        val first = planner.plan(console, listOf(urgent), emptySet(), start)
        assertEquals(1, first.post.size)
        assertTrue(first.post.single().fullScreen)
        assertFalse(first.post.single().silent)
        assertEquals(start + REPEAT_MS, first.nextAt)

        assertTrue("nothing new before the repeat", planner.plan(console, listOf(urgent), emptySet(), start + 1_000).post.isEmpty())
        val again = planner.plan(console, listOf(urgent), emptySet(), start + REPEAT_MS)
        assertEquals(AlertLevel.INSISTENT, again.post.single().route.level)
        assertFalse("never a second full screen for the same incident", again.post.single().fullScreen)

        val taken = urgent.copy(status = AckStatus.ACKNOWLEDGED, teamName = "Medical 1")
        val done = planner.plan(console, listOf(taken), emptySet(), start + REPEAT_MS + 1_000)
        assertTrue(done.post.isEmpty())
        assertEquals("the alarm comes down", listOf("a"), done.stop)
        assertTrue("and stays down", planner.plan(console, listOf(taken), emptySet(), start + 10 * REPEAT_MS).stop.isEmpty())
    }

    @Test
    fun aReopenAlertsAgainAfterTheCooldownButNotOnTheFullScreen() {
        val planner = AlertPlanner(start)
        planner.plan(console, listOf(incident()), emptySet(), start)
        val closed = incident(status = AckStatus.CLOSED_BY_CONTROL)
        planner.plan(console, listOf(closed), emptySet(), start + 5_000)
        val reopened = incident(status = AckStatus.REOPENED, epoch = 1, statusAt = start + 10_000)
        val early = planner.plan(console, listOf(reopened), emptySet(), start + 10_000)
        assertTrue("within the cooldown", early.post.isEmpty())
        assertEquals(start + COOLDOWN_MS, early.nextAt)
        val late = planner.plan(console, listOf(reopened), emptySet(), start + COOLDOWN_MS)
        assertEquals(1, late.post.size)
        assertFalse(late.post.single().fullScreen)
    }

    @Test
    fun aFieldPhoneFeelsAnUrgencyOnceAsItWidensAndAgainWhenItIsAssigned() {
        val planner = AlertPlanner(start)
        val urgent = incident(zone = "bar")
        assertTrue(planner.plan(medic, listOf(urgent), emptySet(), start).post.isEmpty())
        val role = planner.plan(medic, listOf(urgent), emptySet(), start + WIDEN_AFTER_MS)
        assertEquals(Route(AlertLevel.VIBRATE, AlertReason.ROLE), role.post.single().route)
        assertFalse(role.post.single().fullScreen)
        assertTrue("widening to everyone is no news here", planner.plan(medic, listOf(urgent), emptySet(), start + 2 * WIDEN_AFTER_MS).post.isEmpty())
        val assigned = urgent.copy(status = AckStatus.ACKNOWLEDGED, teamName = "Medical 2", assigned = true)
        val news = planner.plan(medic, listOf(assigned), emptySet(), start + 2 * WIDEN_AFTER_MS + 1_000)
        assertEquals(AlertReason.ASSIGNED, news.post.single().route.reason)
        assertTrue("vibration repeats nothing", planner.plan(medic, listOf(assigned), emptySet(), start + 10 * WIDEN_AFTER_MS).post.isEmpty())
    }

    @Test
    fun theFirstWideningIsWhenThePlannerWakesUp() {
        val planner = AlertPlanner(start)
        assertEquals(start + WIDEN_AFTER_MS, planner.plan(medic, listOf(incident(zone = "bar")), emptySet(), start).nextAt)
    }

    @Test
    fun mutedTheConsoleShowsWithoutSoundAndRingsAgainAfter() {
        val planner = AlertPlanner(start)
        val mutedUntil = start + MUTE_MS
        val first = planner.plan(console, listOf(incident()), emptySet(), start, mutedUntil)
        assertTrue(first.post.single().silent)
        assertFalse(first.post.single().fullScreen)
        val during = planner.plan(console, listOf(incident()), emptySet(), start + REPEAT_MS, mutedUntil)
        assertTrue("no repeats while muted", during.post.isEmpty())
        assertEquals(mutedUntil, during.nextAt)
        val after = planner.plan(console, listOf(incident()), emptySet(), mutedUntil, mutedUntil)
        assertFalse(after.post.single().silent)
    }

    @Test
    fun ownReportsAndWhatCameBeforeARestartDontRing() {
        val planner = AlertPlanner(start)
        assertTrue(planner.plan(console, listOf(incident()), setOf("a"), start).post.isEmpty())
        val old = incident(id = "old", receivedAt = start - 60_000)
        val restarted = planner.plan(console, listOf(old), emptySet(), start)
        assertTrue(restarted.post.isEmpty())
        assertEquals("an old urgency still repeats, from the restart", start + REPEAT_MS, restarted.nextAt)
        assertFalse(planner.plan(console, listOf(old), emptySet(), start + REPEAT_MS).post.single().fullScreen)
    }

    @Test
    fun aQuietListWakesNobody() {
        val planner = AlertPlanner(start)
        val closed = incident(status = AckStatus.RESOLVED, team = "Medical 1")
        val plan = planner.plan(medic, listOf(closed), emptySet(), start)
        assertTrue(plan.post.isEmpty())
        assertTrue(plan.stop.isEmpty())
        assertNull(plan.nextAt)
    }
}
