package ro.safetyplease.core.control

import kotlinx.coroutines.test.runTest
import ro.safetyplease.core.TempDir
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandOverTest {
    private val tmp = TempDir()

    @AfterTest
    fun deleteTemp() = tmp.delete()

    private val t0 = 1_760_000_000_000L
    private fun min(m: Int) = t0 + m * 60_000L

    private val incident = StaffIncident(
        incidentId = "case", category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = "main-stage",
        reportedAt = min(0), receivedAt = min(1), hops = 2, status = AckStatus.HANDED_OVER, teamName = "Medical 2", statusAt = min(50),
    )

    /** Margate's afternoon, as a console would have logged it. */
    private val log = listOf(
        LogEntry(min(1), LogEvent.REPORT, "case", reportedAt = min(0)),
        LogEntry(min(1), LogEvent.ACK, "case", status = AckStatus.RECEIVED, team = "Steward 1", signedAt = min(1)),
        LogEntry(min(2), LogEvent.ACK, "case", status = AckStatus.RECEIVED, team = "Medical 1", signedAt = min(2)),
        LogEntry(min(3), LogEvent.ASSIGN, "case", team = "Medical 2"),
        LogEntry(min(3), LogEvent.ACK, "case", status = AckStatus.ACKNOWLEDGED, team = "Medical 2", signedAt = min(3), own = true),
        LogEntry(min(4), LogEvent.ACK, "case", status = AckStatus.ACKNOWLEDGED, team = "Medical 2", signedAt = min(4)),
        LogEntry(min(9), LogEvent.ACK, "case", status = AckStatus.ON_SCENE, team = "Medical 2", signedAt = min(9)),
        LogEntry(min(50), LogEvent.ACK, "case", status = AckStatus.HANDED_OVER, team = "Medical 2", signedAt = min(50)),
    )

    private val notes = listOf(
        HandOverNote("case", min(11), HandOverMark.TEMPERATURE, "40,6"),
        HandOverNote("case", min(10), HandOverMark.FOUND),
        HandOverNote("case", min(11), HandOverMark.PULSE, "186"),
        HandOverNote("other", min(12), HandOverMark.NOTE, "not this one"),
        HandOverNote("case", min(20), HandOverMark.AMBULANCE_CALLED),
        HandOverNote("case", min(56), HandOverMark.LEFT_SITE),
    )

    @Test
    fun theCardPutsTheWholeChainInTimeOrder() {
        val card = HandOver.card(incident, log, notes, "case")
        assertEquals(card.lines.map { it.at }, card.lines.map { it.at }.sorted())
        assertEquals(
            listOf(
                CardStep.REPORTED, CardStep.OPENED, CardStep.RECEIVED, CardStep.ASSIGNED, CardStep.TAKEN, CardStep.ON_SCENE,
                CardStep.MARK, CardStep.MARK, CardStep.MARK, CardStep.MARK, CardStep.HANDED_OVER, CardStep.MARK,
            ),
            card.lines.map { it.step },
        )
        assertEquals("Steward 1", card.lines.single { it.step == CardStep.RECEIVED }.team, "the first receipt only")
        assertEquals("Medical 2", card.lines.single { it.step == CardStep.ASSIGNED }.team)
        assertEquals(min(4), card.lines.single { it.step == CardStep.TAKEN }.at, "the console's own ACK is its decision, not a take")
        assertEquals(min(50), card.lines.single { it.step == CardStep.HANDED_OVER }.at)
        assertEquals(
            listOf(HandOverMark.FOUND, HandOverMark.TEMPERATURE, HandOverMark.PULSE, HandOverMark.AMBULANCE_CALLED, HandOverMark.LEFT_SITE),
            card.lines.mapNotNull { it.mark },
            "the notes of this incident, by time; at the same minute in the order noted",
        )
        assertEquals(40.6, card.temperatureC)
        assertNull(card.last(HandOverMark.COOLING_STARTED), "no cooling: the pre-alert says so")
    }

    @Test
    fun aFieldPhoneWithoutTheLogShowsTheIncidentsOwnState() {
        val card = HandOver.card(incident, emptyList(), emptyList(), "case")
        assertEquals(listOf(CardStep.REPORTED, CardStep.OPENED, CardStep.HANDED_OVER), card.lines.map { it.step })
        assertEquals("Medical 2", card.lines.last().team)
        assertNull(card.temperatureC)
    }

    @Test
    fun notesStayOnThePhoneForThreeDaysAtMostAndCanBeTakenBack() = runTest {
        val store = JsonStore(tmp.root / "handover.json", HandOverNotes.serializer(), HandOverNotes(), backgroundScope)
        val book = HandOverBook(store)
        book.note("case", HandOverMark.TEMPERATURE, min(0), "  40.6  ")
        book.note("case", HandOverMark.NOTE, min(1), "x".repeat(200))
        assertEquals("40.6", book.notes("case").first().value)
        assertEquals(HandOver.MAX_VALUE_CHARS, book.notes("case").last().value.length)
        book.remove(book.notes("case").last())
        assertEquals(1, book.notes("case").size)
        book.prune(min(0) + HandOver.KEEP_MS - 1)
        assertEquals(1, book.notes("case").size)
        book.prune(min(0) + HandOver.KEEP_MS)
        assertTrue(book.notes("case").isEmpty())
    }
}
