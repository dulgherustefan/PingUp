package ro.safetyplease.core.control

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.protocol.AckStatus

/**
 * What the medical team notes on the hand-over card, each at the moment it is noted. The list follows the report
 * (found, at the post, temperature, pulse, score, cooling, ambulance, pre-alert, left); the medical lead fixes it.
 */
@Serializable
enum class HandOverMark { FOUND, AT_POST, TEMPERATURE, PULSE, SCORE, COOLING_STARTED, AMBULANCE_CALLED, HOSPITAL_PREALERTED, HANDED_TO, LEFT_SITE, NOTE }

/** [value]: the number of a measure, the crew or hospital of [HandOverMark.HANDED_TO], the text of a note. */
@Serializable
data class HandOverNote(val incidentId: String, val at: Long, val mark: HandOverMark, val value: String = "")

/** Kept on this phone only: health data, never sent and never in the edition report. */
@Serializable
data class HandOverNotes(val notes: List<HandOverNote> = emptyList())

/** A line of the card; the times are each from the clock that knew them (the reporter's, a team's, this phone's). */
enum class CardStep { REPORTED, OPENED, RECEIVED, ASSIGNED, TAKEN, ON_SCENE, HANDED_OVER, CLOSED, MARK }

data class CardLine(val at: Long, val step: CardStep, val team: String = "", val mark: HandOverMark? = null, val value: String = "")

/** The time-stamped hand-over card of one incident, for the crew that takes the patient on (roadmap row 8). */
data class HandOverCard(val incidentId: String, val lines: List<CardLine>) {
    fun last(mark: HandOverMark): CardLine? = lines.lastOrNull { it.mark == mark }

    /** The latest temperature noted, in °C; null if none or not a number. */
    val temperatureC: Double? get() = last(HandOverMark.TEMPERATURE)?.value?.replace(',', '.')?.toDoubleOrNull()
}

object HandOver {
    /** Notes older than this go: they serve a hand-over, not a record. */
    const val KEEP_MS = 72 * 3_600_000L
    const val MAX_VALUE_CHARS = 60

    /**
     * The card from what this phone holds: the report, the console's log of the incident if it is the console, the
     * reporter's own time, and the team's notes. Lines are in time order; at the same time, in the order of the chain.
     */
    fun card(incident: StaffIncident?, entries: List<LogEntry>, notes: List<HandOverNote>, incidentId: String): HandOverCard {
        val lines = ArrayList<CardLine>()
        val report = entries.firstOrNull { it.event == LogEvent.REPORT }
        val reportedAt = incident?.reportedAt ?: report?.reportedAt
        if (reportedAt != null && reportedAt > 0) lines += CardLine(reportedAt, CardStep.REPORTED)
        (report?.at ?: incident?.receivedAt)?.let { lines += CardLine(it, CardStep.OPENED) }
        var received = false
        for (e in entries) {
            when (e.event) {
                LogEvent.ACK -> {
                    val at = if (e.signedAt > 0) e.signedAt else e.at
                    // what the console signed itself is on the card as the operator's decision
                    val decided = e.own && (e.status == AckStatus.ACKNOWLEDGED || AckStatus.isClosed(e.status))
                    if (!decided) when (e.status) {
                        AckStatus.RECEIVED -> if (!received) {
                            received = true
                            lines += CardLine(at, CardStep.RECEIVED, e.team)
                        }
                        AckStatus.ACKNOWLEDGED -> lines += CardLine(at, CardStep.TAKEN, e.team)
                        AckStatus.ON_SCENE -> lines += CardLine(at, CardStep.ON_SCENE, e.team)
                        AckStatus.HANDED_OVER -> lines += CardLine(at, CardStep.HANDED_OVER, e.team)
                        else -> if (AckStatus.isClosed(e.status)) lines += CardLine(at, CardStep.CLOSED, e.team)
                    }
                }
                LogEvent.ASSIGN -> lines += CardLine(e.at, CardStep.ASSIGNED, e.team)
                LogEvent.TAKE -> lines += CardLine(e.at, CardStep.TAKEN, e.team)
                LogEvent.RESOLVE, LogEvent.UNFOUNDED, LogEvent.CANCEL -> lines += CardLine(e.at, CardStep.CLOSED, e.team)
                else -> Unit
            }
        }
        // a field phone has no log: the incident's own state is all it knows of the chain
        if (entries.isEmpty() && incident != null && incident.statusAt > 0) {
            val step = when (incident.status) {
                AckStatus.ACKNOWLEDGED -> CardStep.TAKEN
                AckStatus.ON_SCENE -> CardStep.ON_SCENE
                AckStatus.HANDED_OVER -> CardStep.HANDED_OVER
                else -> if (AckStatus.isClosed(incident.status)) CardStep.CLOSED else null
            }
            step?.let { lines += CardLine(incident.statusAt, it, incident.teamName) }
        }
        notes.filter { it.incidentId == incidentId }.forEach { lines += CardLine(it.at, CardStep.MARK, mark = it.mark, value = it.value) }
        return HandOverCard(incidentId, lines.sortedWith(compareBy<CardLine> { it.at }.thenBy { it.step.ordinal }))
    }
}

/** The team's notes, on this phone only. */
class HandOverBook(private val store: JsonStore<HandOverNotes>) {
    val state get() = store.state

    fun note(incidentId: String, mark: HandOverMark, atMs: Long, value: String = "") {
        val note = HandOverNote(incidentId, atMs, mark, value.trim().take(HandOver.MAX_VALUE_CHARS))
        store.update { HandOverNotes((it.notes.filter { n -> atMs - n.at < HandOver.KEEP_MS } + note)) }
    }

    /** Takes back a note added by mistake. */
    fun remove(note: HandOverNote) = store.update { HandOverNotes(it.notes - note) }

    fun notes(incidentId: String): List<HandOverNote> = store.value.notes.filter { it.incidentId == incidentId }

    fun prune(nowMs: Long) {
        if (store.value.notes.any { nowMs - it.at >= HandOver.KEEP_MS }) store.update { HandOverNotes(it.notes.filter { n -> nowMs - n.at < HandOver.KEEP_MS }) }
    }
}
