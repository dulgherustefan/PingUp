package ro.safetyplease.app.ui.incidents

import ro.safetyplease.ui.incidents.listRows
import ro.safetyplease.ui.incidents.IncidentList
import ro.safetyplease.ui.incidents.incidentRows
import ro.safetyplease.ui.incidents.isAssignedToMe
import ro.safetyplease.ui.incidents.isMine
import ro.safetyplease.ui.incidents.isOpen
import ro.safetyplease.ui.incidents.nextFieldStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Routing
import ro.safetyplease.core.incidents.StaffProfile
import ro.safetyplease.core.incidents.StatusFilter
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity

class IncidentRowsTest {
    private fun incident(
        id: String,
        atMin: Int,
        severity: Int = Severity.URGENT,
        status: Int = AckStatus.RECEIVED,
        category: Int = IncidentCategory.MEDICAL,
        team: String = "",
        assigned: Boolean = false,
        zone: String = "main-stage",
    ) = StaffIncident(
        incidentId = id, category = category, severity = severity, zone = zone,
        reportedAt = atMin * 60_000L, receivedAt = atMin * 60_000L, hops = 1, status = status, teamName = team, assigned = assigned,
    )

    @Test
    fun whatTheConsoleGaveThisTeamComesFirst() {
        val staff = listOf(
            incident("nepreluat", 3),
            incident("alocat-altora", 2, status = AckStatus.ACKNOWLEDGED, team = "Securitate 1"),
            incident("alocat-rolului", 1, severity = Severity.MEDIUM, status = AckStatus.ACKNOWLEDGED, team = "Medical"),
            incident("alocat-echipei", 0, severity = Severity.MEDIUM, status = AckStatus.ACKNOWLEDGED, team = "Medical 2"),
            incident("rezolvat-de-echipa", 4, status = AckStatus.RESOLVED, team = "Medical 2"),
        )
        assertEquals(
            listOf("alocat-rolului", "alocat-echipei", "nepreluat", "alocat-altora"),
            incidentRows(staff, StatusFilter.ACTIVE, team = "Medical 2").map { it.incidentId },
        )
        assertEquals("without a team, the order stays", "nepreluat", incidentRows(staff, StatusFilter.ACTIVE).first().incidentId)
        assertFalse("closed work isn't the team's any more", staff.last().isMine("Medical 2"))
    }

    @Test
    fun onlyTheConsolesAssignmentReadsAsAssignedToTheTeam() {
        val assigned = incident("alocat", 0, status = AckStatus.ACKNOWLEDGED, team = "Medical", assigned = true)
        val taken = incident("preluat", 1, status = AckStatus.ACKNOWLEDGED, team = "Medical 2")
        assertTrue(assigned.isAssignedToMe("Medical 2"))
        assertFalse("the team's own take is not an assignment", taken.isAssignedToMe("Medical 2"))
        assertTrue("but it is still the team's work", taken.isMine("Medical 2"))
        assertFalse("assigned to another team", assigned.isAssignedToMe("Securitate 1"))
        assertFalse("closed", assigned.copy(status = AckStatus.RESOLVED).isAssignedToMe("Medical 2"))
    }

    @Test
    fun twoMedicalCasesInTheSameZoneAreTwoRows() {
        val rows = incidentRows(listOf(incident("a", 0), incident("b", 1)), StatusFilter.ACTIVE)
        assertEquals("newest first", listOf("b", "a"), rows.map { it.incidentId })
    }

    @Test
    fun takingOneCaseMovesOnlyThatRowBelowTheUnassignedOnes() {
        val rows = incidentRows(
            listOf(
                incident("preluat", 2, status = AckStatus.ACKNOWLEDGED),
                incident("nepreluat", 1),
                incident("mediu", 3, severity = Severity.MEDIUM),
            ),
            StatusFilter.ACTIVE,
        )
        assertEquals(listOf("nepreluat", "preluat", "mediu"), rows.map { it.incidentId })
    }

    @Test
    fun fieldStatesAreStillTheTeamsWorkOneStepAtATime() {
        val onScene = incident("la-fata-locului", 0, status = AckStatus.ON_SCENE, team = "Medical 2")
        assertTrue(onScene.isMine("Medical 2"))
        assertFalse(onScene.isOpen())
        assertTrue("a reopened incident waits for a team again", incident("redeschis", 1, status = AckStatus.REOPENED).isOpen())
        assertEquals(AckStatus.ON_SCENE, nextFieldStep(AckStatus.ACKNOWLEDGED))
        assertEquals(AckStatus.HANDED_OVER, nextFieldStep(AckStatus.ON_SCENE))
        for (status in listOf(AckStatus.RECEIVED, AckStatus.HANDED_OVER, AckStatus.CLOSED_BY_CONTROL, AckStatus.REOPENED)) {
            assertEquals(null, nextFieldStep(status))
        }
        val closedByControl = incident("inchis", 2, status = AckStatus.CLOSED_BY_CONTROL)
        assertEquals(listOf("inchis"), incidentRows(listOf(onScene, closedByControl), StatusFilter.RESOLVED).map { it.incidentId })
    }

    @Test
    fun aLostFriendIsNotOnTheFieldList() {
        val rows = incidentRows(
            listOf(incident("prieten", 0, Severity.LOW, category = IncidentCategory.LOST_FRIEND), incident("copil", 1, category = IncidentCategory.LOST_CHILD)),
            StatusFilter.ACTIVE,
        )
        assertEquals(listOf("copil"), rows.map { it.incidentId })
    }

    @Test
    fun theFieldPhoneSeesWhatIsRoutedToItFirstAndTheRestUnderAll() {
        val me = StaffProfile(console = false, team = "Medical 2", zone = "main-stage")
        val staff = listOf(
            incident("urgent-in-zona", 3),
            incident("mediu-in-zona", 4, severity = Severity.MEDIUM),
            incident("alocat", 3, severity = Severity.MEDIUM, status = AckStatus.ACKNOWLEDGED, team = "Medical", assigned = true),
            incident("urgent-altundeva", 4, zone = "camping"),
            incident("rezolvat", 2, status = AckStatus.RESOLVED, team = "Medical 2"),
        )
        val now = 4 * 60_000L + 10_000
        assertEquals(listOf("alocat", "urgent-in-zona"), listRows(staff, IncidentList.FOR_ME, me, now).map { it.incidentId })
        assertEquals(
            "everything open, what is routed here first",
            listOf("alocat", "urgent-in-zona", "urgent-altundeva", "mediu-in-zona"),
            listRows(staff, IncidentList.ALL, me, now).map { it.incidentId },
        )
        assertEquals(listOf("rezolvat"), listRows(staff, IncidentList.CLOSED, me, now).map { it.incidentId })
        val later = now + Routing.WIDEN_AFTER_MS
        assertTrue(
            "nobody took them: the role's phones see them too",
            listRows(staff, IncidentList.FOR_ME, me, later).map { it.incidentId }.containsAll(listOf("urgent-altundeva", "mediu-in-zona")),
        )
    }

    @Test
    fun anAssignmentIsAcceptedByTheTeamsOwnTake() {
        val assigned = incident("alocat", 0, status = AckStatus.ACKNOWLEDGED, team = "Medical", assigned = true)
        assertTrue(Routing.awaitsAcceptance(assigned, "Medical 2"))
        assertFalse("accepted: the take replaces the assignment", Routing.awaitsAcceptance(assigned.copy(assigned = false, teamName = "Medical 2"), "Medical 2"))
        assertFalse("on scene says it too", Routing.awaitsAcceptance(assigned.copy(status = AckStatus.ON_SCENE), "Medical 2"))
        assertFalse(Routing.awaitsAcceptance(assigned, "Securitate 1"))
    }

    @Test
    fun crowdReportsAreRowsOfTheirOwnToo() {
        val crowd = (0..2).map { incident("aglomeratie-$it", it, category = IncidentCategory.CROWD) } +
            incident("rezolvat", 4, category = IncidentCategory.CROWD, status = AckStatus.RESOLVED)
        assertEquals(listOf("aglomeratie-2", "aglomeratie-1", "aglomeratie-0"), incidentRows(crowd, StatusFilter.ACTIVE).map { it.incidentId })
        assertEquals(listOf("rezolvat"), incidentRows(crowd, StatusFilter.RESOLVED).map { it.incidentId })
    }
}
