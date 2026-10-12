package ro.safetyplease.app.ui.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.data.StaffIncident
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
    ) = StaffIncident(
        incidentId = id, category = category, severity = severity, zone = "main-stage",
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
    fun crowdReportsAreRowsOfTheirOwnToo() {
        val crowd = (0..2).map { incident("aglomeratie-$it", it, category = IncidentCategory.CROWD) } +
            incident("rezolvat", 4, category = IncidentCategory.CROWD, status = AckStatus.RESOLVED)
        assertEquals(listOf("aglomeratie-2", "aglomeratie-1", "aglomeratie-0"), incidentRows(crowd, StatusFilter.ACTIVE).map { it.incidentId })
        assertEquals(listOf("rezolvat"), incidentRows(crowd, StatusFilter.RESOLVED).map { it.incidentId })
    }
}
