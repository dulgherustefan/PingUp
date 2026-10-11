package ro.safetyplease.app.ui.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity

class ControlLogicTest {
    private val t0 = 1_791_832_500_000L

    private fun report(
        severity: Int = Severity.URGENT,
        atMin: Int = 0,
        grid: String = "E2",
        description: String = "a leșinat lângă turnul de lumini, tricou roșu",
        category: Int = IncidentCategory.MEDICAL,
    ) = LogEntry(
        t0 + atMin * 60_000L, LogEvent.REPORT, "id$atMin$severity", category = category, severity = severity, zone = "main-stage",
        grid = grid, reportedAt = t0 + atMin * 60_000L, description = description,
    )

    private fun case(
        id: String,
        severity: Int,
        atMin: Int,
        state: CaseState = CaseState.OPEN,
        network: Int = AckStatus.RECEIVED,
        closedAtMin: Int? = null,
    ) = Case(
        report(severity, atMin).copy(incidentId = id), state, "", false, network, "",
        null, null, closedAtMin?.let { t0 + it * 60_000L },
    )

    // --- the queue ---

    @Test
    fun queueShowsTheMostUrgentFirstThenTheOneWaitingLongest() {
        val cases = listOf(
            case("mediu-vechi", Severity.MEDIUM, 0),
            case("urgent-nou", Severity.URGENT, 10),
            case("urgent-vechi", Severity.URGENT, 2),
            case("alocat", Severity.URGENT, 1, CaseState.ASSIGNED),
        )
        assertEquals(listOf("urgent-vechi", "urgent-nou", "mediu-vechi"), queue(cases, CaseState.OPEN).map { it.incidentId })
        assertEquals(listOf("alocat"), queue(cases, CaseState.ASSIGNED).map { it.incidentId })
    }

    @Test
    fun closedOnesShowTheLastClosedFirst() {
        val cases = listOf(
            case("inchis-devreme", Severity.URGENT, 0, CaseState.CLOSED, closedAtMin = 5),
            case("inchis-tarziu", Severity.MEDIUM, 1, CaseState.CLOSED, closedAtMin = 30),
            case("fara-ora", Severity.URGENT, 20, CaseState.CLOSED),
        )
        assertEquals(listOf("inchis-tarziu", "fara-ora", "inchis-devreme"), queue(cases, CaseState.CLOSED).map { it.incidentId })
    }

    // --- actions ---

    @Test
    fun theNextStepComesFirstAndAClosedIncidentCanOnlyBeReopened() {
        assertEquals(listOf(Action.ASSIGN, Action.TAKE, Action.RESOLVE, Action.UNFOUNDED), actions(case("a", Severity.URGENT, 0)))
        assertEquals(Action.RESOLVE, actions(case("a", Severity.URGENT, 0, CaseState.ASSIGNED)).first())
        assertEquals(listOf(Action.REOPEN), actions(case("a", Severity.URGENT, 0, CaseState.CLOSED)))
    }

    @Test
    fun decisionsGoOutOnlyWhileTheNetworkIsBehind() {
        assertEquals(AckStatus.ACKNOWLEDGED, ackFor(Action.ASSIGN, AckStatus.RECEIVED))
        assertEquals(AckStatus.ACKNOWLEDGED, ackFor(Action.TAKE, AckStatus.NONE))
        assertNull("no reassignment on the network yet", ackFor(Action.ASSIGN, AckStatus.ACKNOWLEDGED))
        assertEquals(AckStatus.RESOLVED, ackFor(Action.RESOLVE, AckStatus.ACKNOWLEDGED))
        assertEquals("the network only knows resolved", AckStatus.RESOLVED, ackFor(Action.UNFOUNDED, AckStatus.RECEIVED))
        assertNull(ackFor(Action.UNFOUNDED, AckStatus.RESOLVED))
        assertNull("a status never goes back down", ackFor(Action.REOPEN, AckStatus.RESOLVED))
    }

    @Test
    fun teamsHeardComeFirstThenTheRolesNeverTheConsole() {
        val entries = listOf(
            LogEntry(1, LogEvent.ACK, "a", status = AckStatus.RECEIVED, team = "Control"),
            LogEntry(2, LogEvent.ACK, "a", status = AckStatus.ACKNOWLEDGED, team = "Medical 1"),
            LogEntry(3, LogEvent.ASSIGN, "b", team = "Securitate 2"),
            LogEntry(4, LogEvent.ACK, "c", status = AckStatus.RESOLVED, team = "medical 1"),
            LogEntry(5, LogEvent.REPORT, "d", team = "ignorat"),
        )
        val roles = listOf("Medical", "Securitate", "Control")
        assertEquals(
            listOf("medical 1", "Securitate 2", "Medical", "Securitate"),
            teamChoices(entries, roles, own = "Control"),
        )
        assertEquals(2, teamChoices(entries, roles, own = "Control", max = 2).size)
    }

    // --- the radio phrase ---

    private val words = RadioWords(
        category = { if (it == IncidentCategory.MEDICAL) "Medical" else "Altceva" },
        urgent = "urgent", notUrgent = "neurgent", unknownPlace = "loc necunoscut",
    )

    @Test
    fun radioPhraseFollowsEthaneOrder() {
        assertEquals(
            "Medical, urgent, E2, Scena principală, a leșinat lângă turnul de lumini, tricou roșu.",
            radioPhrase(report(), "Scena principală", words),
        )
    }

    @Test
    fun radioPhraseLeavesOutWhatTheReportDoesNotSay() {
        assertEquals("Medical, neurgent, Scena principală.", radioPhrase(report(Severity.MEDIUM, grid = "", description = ""), "Scena principală", words))
        assertEquals("Altceva, urgent, loc necunoscut.", radioPhrase(report(grid = "", description = "", category = IncidentCategory.OTHER), "", words))
        assertEquals("Medical, urgent, E2.", radioPhrase(report(description = "   "), "", words))
    }

    @Test
    fun radioPhraseReadsTheDescriptionAsOneLine() {
        val messy = report(description = "  cade mereu\n\nlângă   bar!!  ")
        assertEquals("Medical, urgent, E2, Baruri, cade mereu lângă bar.", radioPhrase(messy, "Baruri", words))
    }
}
