package ro.safetyplease.core.metrics

import kotlinx.serialization.Serializable
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.venue.SiteGrid

/** A person has the incident: a team took it, or already resolved it. */
fun isHandled(status: Int): Boolean = AckStatus.isTaken(status) || status == AckStatus.RESOLVED

/**
 * Times an ACK from another phone on the reporter's clock: the first one of any status, and the first saying a person
 * has the incident. Later ACKs leave both as they are.
 */
fun MyReport.timedAck(status: Int, hops: Int, nowMs: Long): MyReport {
    val first = receivedAt == 0L
    val handled = acknowledgedAt == 0L && isHandled(status)
    if (!first && !handled) return this
    return copy(
        receivedAt = if (first) nowMs else receivedAt,
        firstAckHops = if (first) hops else firstAckHops,
        acknowledgedAt = if (handled) nowMs else acknowledgedAt,
    )
}

/**
 * One of the reporter's own reports as the pilot measures it: no nodeId, nickname, description or coordinates, only
 * the square of the site grid. The durations are on the reporter's clock alone; null while the ACK hasn't come.
 */
@Serializable
data class ReportTiming(
    val incidentId: String,
    val createdAt: Long,
    val category: Int,
    val severity: Int,
    val grid: String = "",
    val status: Int = 0,
    val epoch: Int = 0,
    val sent: Boolean = false,
    val cancelled: Boolean = false,
    val msToReceived: Long? = null,
    val msToAcknowledged: Long? = null,
    val firstAckHops: Int? = null,
    val republished: Int = 0,
) {
    companion object {
        fun of(report: MyReport, grid: SiteGrid): ReportTiming {
            val lat = report.lat
            val lon = report.lon
            return ReportTiming(
                report.incidentId, report.createdAt, report.category, report.severity,
                if (lat != null && lon != null) grid.cellAt(lat, lon).orEmpty() else "",
                report.status, report.epoch, report.sent, report.cancelled,
                report.receivedAt.takeIf { it > 0 }?.let { it - report.createdAt },
                report.acknowledgedAt.takeIf { it > 0 }?.let { it - report.createdAt },
                report.firstAckHops.takeIf { report.receivedAt > 0 },
                report.republished,
            )
        }
    }
}
