package ro.safetyplease.app.ui.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.control.Case
import ro.safetyplease.core.control.CaseState
import ro.safetyplease.core.control.LogEntry
import ro.safetyplease.core.control.LogEvent
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.ui.control.Action
import ro.safetyplease.ui.control.RadioWords
import ro.safetyplease.ui.control.ackFor
import ro.safetyplease.ui.control.actions
import ro.safetyplease.ui.control.crowdSignals
import ro.safetyplease.ui.control.mutedMinutes
import ro.safetyplease.ui.control.queue
import ro.safetyplease.ui.control.radioPhrase
import ro.safetyplease.ui.control.reopened
import ro.safetyplease.ui.control.teamChoices
import ro.safetyplease.ui.control.urgent

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
        epoch: Int = 0,
        cancelRequested: Boolean = false,
    ) = Case(
        report(severity, atMin).copy(incidentId = id), state, "", false, network, epoch, "",
        null, null, closedAtMin?.let { t0 + it * 60_000L }, cancelRequested,
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
        assertEquals(listOf(Action.ASSIGN, Action.TAKE, Action.CLOSE, Action.UNFOUNDED), actions(case("a", Severity.URGENT, 0)))
        assertEquals(Action.CLOSE, actions(case("a", Severity.URGENT, 0, CaseState.ASSIGNED)).first())
        assertEquals(listOf(Action.REOPEN), actions(case("a", Severity.URGENT, 0, CaseState.CLOSED)))
        assertEquals("the epoch is one byte", emptyList<Action>(), actions(case("a", Severity.URGENT, 0, CaseState.CLOSED, epoch = 255)))
    }

    @Test
    fun aCancelToCheckPutsItsConfirmationFirst() {
        val requested = case("a", Severity.URGENT, 0, cancelRequested = true)
        assertEquals(listOf(Action.CONFIRM_CANCEL, Action.ASSIGN, Action.TAKE, Action.CLOSE, Action.UNFOUNDED), actions(requested))
        assertEquals(Action.CONFIRM_CANCEL, actions(requested.copy(state = CaseState.ASSIGNED)).first())
    }

    @Test
    fun decisionsGoOutOnlyWhileTheNetworkIsBehind() {
        assertEquals(AckStatus.ACKNOWLEDGED, ackFor(Action.ASSIGN, AckStatus.RECEIVED))
        assertEquals(AckStatus.ACKNOWLEDGED, ackFor(Action.TAKE, AckStatus.NONE))
        assertEquals("after a reopen", AckStatus.ACKNOWLEDGED, ackFor(Action.ASSIGN, AckStatus.REOPENED))
        assertNull("no reassignment on the network yet", ackFor(Action.ASSIGN, AckStatus.ACKNOWLEDGED))
        assertNull(ackFor(Action.ASSIGN, AckStatus.ON_SCENE))
        assertEquals(AckStatus.CLOSED_BY_CONTROL, ackFor(Action.CLOSE, AckStatus.HANDED_OVER))
        assertEquals("control closes what a team resolved", AckStatus.CLOSED_BY_CONTROL, ackFor(Action.CLOSE, AckStatus.RESOLVED))
        assertEquals(AckStatus.UNFOUNDED, ackFor(Action.UNFOUNDED, AckStatus.RECEIVED))
        assertEquals(AckStatus.UNFOUNDED, ackFor(Action.UNFOUNDED, AckStatus.CLOSED_BY_CONTROL))
        assertNull(ackFor(Action.UNFOUNDED, AckStatus.CANCELLED))
        assertEquals(AckStatus.CANCELLED, ackFor(Action.CONFIRM_CANCEL, AckStatus.ACKNOWLEDGED))
        assertEquals("a reopen starts the next epoch, whatever closed it", AckStatus.REOPENED, ackFor(Action.REOPEN, AckStatus.CANCELLED))
    }

    @Test
    fun reopenedOnTheNetworkReadsAsReopened() {
        assertTrue(case("a", Severity.URGENT, 0, network = AckStatus.REOPENED, epoch = 1).reopened)
        assertFalse(case("a", Severity.URGENT, 0).reopened)
        assertFalse(case("a", Severity.URGENT, 0, CaseState.CLOSED, network = AckStatus.REOPENED).reopened)
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
        triage = { r, b -> "răspunde: ${answers[r]}, respiră normal: ${answers[b]}" },
        people = { "$it persoane" }, dangerContinues = "pericolul continuă",
        near = { "lângă $it" }, recognition = { "raportor cu $it" },
    )
    private val answers = listOf("fără răspuns", "da", "nu", "nu știu")

    @Test
    fun radioPhraseFollowsEthaneOrder() {
        assertEquals(
            "Medical, urgent, E2, Scena principală, a leșinat lângă turnul de lumini, tricou roșu.",
            radioPhrase(report(), "Scena principală", "", words),
        )
    }

    @Test
    fun radioPhraseLeavesOutWhatTheReportDoesNotSay() {
        assertEquals("Medical, neurgent, Scena principală.", radioPhrase(report(Severity.MEDIUM, grid = "", description = ""), "Scena principală", "", words))
        assertEquals("Altceva, urgent, loc necunoscut.", radioPhrase(report(grid = "", description = "", category = IncidentCategory.OTHER), "", "", words))
        assertEquals("Medical, urgent, E2.", radioPhrase(report(description = "   "), "", "", words))
    }

    @Test
    fun radioPhraseCarriesTriageAndWhatStaffAdded() {
        val triaged = report().copy(responds = Answer.NO, breathes = Answer.UNKNOWN)
        assertEquals(
            "Medical, urgent, răspunde: nu, respiră normal: nu știu, E2, Scena principală, a leșinat lângă turnul de lumini, tricou roșu.",
            radioPhrase(triaged, "Scena principală", "", words),
        )
        val staff = report(description = "acces pe la poarta 2", category = IncidentCategory.VIOLENCE)
            .copy(fromStaff = true, people = 3, dangerContinues = true)
        assertEquals("Altceva, urgent, E2, 3 persoane, pericolul continuă, acces pe la poarta 2.", radioPhrase(staff, "", "", words))
    }

    @Test
    fun theMuteCountsDownInWholeMinutesAndNeverPastTen() {
        assertEquals(0, mutedMinutes(0, t0))
        assertEquals(10, mutedMinutes(t0 + 10 * 60_000L, t0))
        assertEquals("a stale clock doesn't show eleven", 10, mutedMinutes(t0 + 10 * 60_000L + 20_000, t0))
        assertEquals(1, mutedMinutes(t0 + 1_000, t0))
        assertEquals(0, mutedMinutes(t0, t0))
    }

    @Test
    fun radioPhraseReadsTheDescriptionAsOneLine() {
        val messy = report(description = "  cade mereu\n\nlângă   bar!!  ")
        assertEquals("Medical, urgent, E2, Baruri, cade mereu lângă bar.", radioPhrase(messy, "Baruri", "", words))
    }

    @Test
    fun radioPhraseNamesTheLandmarkAndHowToSpotTheReporter() {
        val described = report(description = Recognition.join("tricou roșu", "a leșinat"))
        assertEquals(
            "Medical, urgent, E2 lângă Toalete camping, Scena principală, raportor cu tricou roșu, a leșinat.",
            radioPhrase(described, "Scena principală", "Toalete camping", words),
        )
        assertEquals("Medical, urgent, lângă Toalete camping.", radioPhrase(report(grid = "", description = ""), "", "Toalete camping", words))
    }

    // --- crowd signal ---

    private fun crowdCase(id: String, atMin: Int, state: CaseState = CaseState.OPEN, zone: String = "main-stage") = Case(
        report(Severity.MEDIUM, atMin, category = IncidentCategory.CROWD).copy(incidentId = id, zone = zone), state, "", false,
        AckStatus.RECEIVED, 0, "", null, null, null,
    )

    @Test
    fun crowdReportsOfOneZoneBecomeASignalAtTheQueue() {
        val cases = (0 until 5).map { crowdCase("c$it", it) }
        val signal = crowdSignals(cases, t0 + 5 * 60_000L).single()
        assertEquals("main-stage", signal.zone)
        assertEquals(5, signal.count)
        assertEquals(4, signal.minutes)
    }

    @Test
    fun closedCrowdCasesAndOtherCategoriesDoNotCount() {
        val cases = (0 until 4).map { crowdCase("c$it", it) } + crowdCase("closed", 4, CaseState.CLOSED) +
            case("medical", Severity.URGENT, 4)
        assertTrue(crowdSignals(cases, t0 + 5 * 60_000L).isEmpty())
        assertEquals("a taken one still counts", 5, crowdSignals(cases + crowdCase("taken", 4, CaseState.ASSIGNED), t0 + 5 * 60_000L).single().count)
    }
}
