package ro.safetyplease.app.ui.incidents

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.StatusFilter
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity

class IncidentRowsTest {
    private fun incident(id: String, atMin: Int, severity: Int = Severity.URGENT, status: Int = AckStatus.RECEIVED, category: Int = IncidentCategory.MEDICAL) =
        StaffIncident(
            incidentId = id, category = category, severity = severity, zone = "main-stage",
            reportedAt = atMin * 60_000L, receivedAt = atMin * 60_000L, hops = 1, status = status,
        )

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
