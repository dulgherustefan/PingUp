package ro.safetyplease.app.ui.report

import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.status_acknowledged
import ro.safetyplease.ui.resources.status_acknowledged_pending
import ro.safetyplease.ui.resources.status_closed_by_control
import ro.safetyplease.ui.resources.status_handed_over
import ro.safetyplease.ui.resources.status_on_scene
import ro.safetyplease.ui.resources.status_received
import ro.safetyplease.ui.resources.status_received_note
import ro.safetyplease.ui.resources.status_reopened
import ro.safetyplease.ui.resources.status_resolved
import ro.safetyplease.ui.resources.status_sending
import ro.safetyplease.ui.resources.status_unfounded
import ro.safetyplease.ui.resources.status_waiting_link
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.ui.isOpen

/** Venue place type of first aid, as in venue.json. */
internal const val MEDICAL = "medical"

/** How far the report got, from 0 (waiting for a phone) to 4 (closed). A reopened incident is back with staff. */
fun reportStep(report: MyReport): Int = when {
    AckStatus.isClosed(report.status) -> 4
    AckStatus.isTaken(report.status) || report.status == AckStatus.REOPENED -> 3
    report.status == AckStatus.RECEIVED -> 2
    report.sent -> 1
    else -> 0
}

/**
 * Label of a timeline step. RECEIVED only means a staff phone got the report, sent automatically, so its step speaks of
 * a phone; "seen" belongs to a person on staff, from ACKNOWLEDGED on. The current step names the status it reached.
 */
fun stepLabel(step: Int, report: MyReport): StringResource = when (step) {
    0 -> Res.string.status_waiting_link
    1 -> Res.string.status_sending
    2 -> Res.string.status_received
    3 -> when (report.status) {
        AckStatus.ON_SCENE -> Res.string.status_on_scene
        AckStatus.HANDED_OVER -> Res.string.status_handed_over
        AckStatus.REOPENED -> Res.string.status_reopened
        else -> if (reportStep(report) >= 3 && report.teamName.isNotEmpty()) Res.string.status_acknowledged else Res.string.status_acknowledged_pending
    }
    else -> when (report.status) {
        AckStatus.CLOSED_BY_CONTROL -> Res.string.status_closed_by_control
        AckStatus.UNFOUNDED -> Res.string.status_unfounded
        else -> Res.string.status_resolved
    }
}

/** What the current step adds under its label: at RECEIVED, that no person has confirmed yet. */
fun stepNote(step: Int): StringResource? = if (step == 2) Res.string.status_received_note else null

/**
 * A team will come to the reporter, who should stay put and be easy to spot: an open medical or urgent report. Not once
 * an urgent one is [escalated], since then the reporter goes to find a steward.
 */
fun staysPut(report: MyReport, escalated: Boolean): Boolean =
    !escalated && report.isOpen() && (report.category == IncidentCategory.MEDICAL || report.severity == Severity.URGENT)
