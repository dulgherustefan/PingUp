package ro.safetyplease.core.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity

class StaffCodesTest {
    private fun incident(
        id: String,
        status: Int,
        team: String = "Steward 3",
        assigned: Boolean = false,
        statusAt: Long = 0,
    ) = StaffIncident(
        incidentId = id, category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = "bar", reportedAt = 0, receivedAt = 0,
        hops = 0, status = status, teamName = team, assigned = assigned, statusAt = statusAt,
    )

    @Test
    fun theThreeReportCodesAreUrgentReportsAndTheOthersAreNot() {
        val medic = StaffCodes.report(StaffCode.MEDIC)!!
        assertEquals(IncidentCategory.MEDICAL, medic.category)
        assertEquals(Severity.URGENT, medic.severity)
        assertEquals(Severity.URGENT, StaffCodes.report(StaffCode.REINFORCEMENTS)!!.severity)
        val danger = StaffCodes.report(StaffCode.STAFF_IN_DANGER)!!
        assertEquals(IncidentCategory.VIOLENCE, danger.category)
        assertTrue("staff in danger says the danger goes on", danger.dangerContinues)
        assertNull(StaffCodes.report(StaffCode.ON_SCENE))
        assertNull(StaffCodes.report(StaffCode.HANDED_OVER))
    }

    @Test
    fun onSceneMovesTheIncidentTheTeamTookMostRecently() {
        val incidents = listOf(
            incident("old", AckStatus.ACKNOWLEDGED, statusAt = 1_000),
            incident("new", AckStatus.ACKNOWLEDGED, statusAt = 5_000),
            incident("theirs", AckStatus.ACKNOWLEDGED, team = "Medical 1", statusAt = 9_000),
            incident("open", AckStatus.RECEIVED, team = "", statusAt = 9_000),
        )
        assertEquals("new", StaffCodes.target(StaffCode.ON_SCENE, incidents, "Steward 3")!!.incidentId)
        assertNull("nothing taken, nothing to move", StaffCodes.target(StaffCode.ON_SCENE, incidents, "Pompieri 1"))
    }

    @Test
    fun anIncidentAlreadyOnSceneIsOnlyLeftForTheHandOver() {
        val incidents = listOf(incident("a", AckStatus.ON_SCENE))
        assertNull(StaffCodes.target(StaffCode.ON_SCENE, incidents, "Steward 3"))
        assertEquals("a", StaffCodes.target(StaffCode.HANDED_OVER, incidents, "Steward 3")!!.incidentId)
        assertNull("handed over is the end of the field steps", StaffCodes.target(StaffCode.HANDED_OVER, listOf(incident("b", AckStatus.HANDED_OVER)), "Steward 3"))
        assertEquals("a team may hand over without having tapped on scene", "c", StaffCodes.target(StaffCode.HANDED_OVER, listOf(incident("c", AckStatus.ACKNOWLEDGED)), "Steward 3")!!.incidentId)
    }

    @Test
    fun anAssignmentTheTeamHasNotAcceptedIsNotTheirsToMoveYet() {
        val assigned = listOf(incident("a", AckStatus.ACKNOWLEDGED, assigned = true))
        assertNull(StaffCodes.target(StaffCode.ON_SCENE, assigned, "Steward 3"))
        assertNull(StaffCodes.target(StaffCode.HANDED_OVER, assigned, "Steward 3"))
    }

    @Test
    fun aClosedIncidentOrOneOfAnotherTeamIsNeverMoved() {
        val incidents = listOf(
            incident("closed", AckStatus.RESOLVED),
            incident("other", AckStatus.ACKNOWLEDGED, team = "Securitate 2"),
        )
        for (code in listOf(StaffCode.ON_SCENE, StaffCode.HANDED_OVER)) assertNull(StaffCodes.target(code, incidents, "Steward 3"))
        assertNull(StaffCodes.target(StaffCode.MEDIC, incidents, "Steward 3"))
    }
}
