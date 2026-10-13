package ro.safetyplease.core.control

import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.metrics.MeshSnapshot
import ro.safetyplease.core.metrics.NetworkSample
import ro.safetyplease.core.metrics.ReportTiming
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.util.formatDecimal
import ro.safetyplease.core.venue.SiteGrid

/** Everything one phone measured for the pilot. [delivery] comes only from the control console. */
@Serializable
class MetricsDocument(
    val format: String,
    val schemaVersion: Int,
    val exportedAt: String,
    val role: Role,
    val venue: String,
    val grid: GridInfo,
    val reports: List<ReportTiming> = emptyList(),
    val snapshots: List<MeshSnapshot> = emptyList(),
    val network: List<NetworkSample> = emptyList(),
    val delivery: List<DeliveryRow> = emptyList(),
)

/**
 * The pilot metrics of one phone, for analysis next to other phones' exports: the reporter's own reports with their
 * round trips, the mesh snapshots and the mobile network log, as CSV and JSON. Nothing names the phone or its owner:
 * no nodeId, nickname, team, description or coordinates. Cells are codes, as in PROTOCOL.md; times are local ISO 8601.
 */
object MetricsExport {
    const val FORMAT = "pingup-metrics"
    const val VERSION = 1

    /** Larger than a phone's export can get; a bigger file is not one. */
    const val MAX_IMPORT_BYTES = 4_000_000

    private val codec = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** Hop columns: the TTL lets a packet go at most this far. */
    private const val MAX_HOPS = 7

    private val TYPES = listOf(
        "hello" to PacketType.HELLO, "summary" to PacketType.SUMMARY, "request" to PacketType.REQUEST,
        "summary2" to PacketType.SUMMARY2, "request2" to PacketType.REQUEST2, "report" to PacketType.INCIDENT_REPORT,
        "ack" to PacketType.INCIDENT_ACK, "cancel" to PacketType.INCIDENT_CANCEL, "private" to PacketType.PRIVATE,
        "announcement" to PacketType.ANNOUNCEMENT, "test" to PacketType.TEST,
    )

    val REPORT_COLUMNS = listOf(
        "incident", "created_at", "category", "severity", "grid", "status", "epoch", "sent", "cancelled",
        "ms_to_received", "ms_to_acknowledged", "first_ack_hops", "republished",
    )

    val MESH_COLUMNS = listOf(
        "time", "period_s", "role", "links", "visible_peers", "cached_reports", "sent", "received", "relayed", "dropped",
        "queue_full", "store_and_forward", "refused_banned", "links_opened", "links_closed", "links_opened_per_hour",
        "links_closed_per_hour",
    ) + DropReason.entries.map { "drop_" + it.name.lowercase() } +
        (1..MAX_HOPS).map { "report_hops_$it" } + (1..MAX_HOPS).map { "ack_hops_$it" } +
        TYPES.map { "sent_" + it.first } + "sent_other" + TYPES.map { "received_" + it.first } + "received_other"

    val NETWORK_COLUMNS = listOf("time", "grid", "level", "dbm", "technology", "transport", "validated", "probe", "probe_ms")

    val DELIVERY_COLUMNS = listOf(
        "incident", "reporter_record", "at_console", "created_at", "console_at", "console_hops", "category", "severity",
        "grid", "ms_to_received", "ms_to_acknowledged", "first_ack_hops", "republished",
    )

    fun reportsCsv(reports: List<ReportTiming>, zone: TimeZone): String {
        val csv = LogExport.Csv(REPORT_COLUMNS)
        for (r in reports.sortedBy { it.createdAt }) {
            csv.row(
                r.incidentId, LogExport.time(r.createdAt, zone), r.category.toString(), r.severity.toString(), r.grid,
                r.status.toString(), r.epoch.toString(), flag(r.sent), flag(r.cancelled), r.msToReceived.cell(),
                r.msToAcknowledged.cell(), r.firstAckHops.cell(), r.republished.toString(),
            )
        }
        return csv.toString()
    }

    fun meshCsv(snapshots: List<MeshSnapshot>, zone: TimeZone): String {
        val csv = LogExport.Csv(MESH_COLUMNS)
        val known = TYPES.map { it.second }.toSet()
        for (s in snapshots) {
            val cells = listOf(
                LogExport.time(s.at, zone), (s.periodMs / 1000).toString(), s.role.name.lowercase(), s.links.toString(),
                s.visiblePeers.toString(), s.cachedReports.toString(), s.sent.toString(), s.received.toString(),
                s.relayed.toString(), s.dropped.toString(), s.queueFull.toString(), s.storeAndForward.toString(),
                s.refusedBanned.toString(), s.linksOpened.toString(), s.linksClosed.toString(),
                rate(s.perHour(s.linksOpened)), rate(s.perHour(s.linksClosed)),
            ) + DropReason.entries.map { (s.drops[it] ?: 0L).toString() } +
                (1..MAX_HOPS).map { (s.reportHops[it] ?: 0L).toString() } + (1..MAX_HOPS).map { (s.ackHops[it] ?: 0L).toString() } +
                byType(s.sentByType, known) + byType(s.receivedByType, known)
            csv.row(*cells.toTypedArray())
        }
        return csv.toString()
    }

    fun networkCsv(samples: List<NetworkSample>, zone: TimeZone): String {
        val csv = LogExport.Csv(NETWORK_COLUMNS)
        for (s in samples) {
            csv.row(
                LogExport.time(s.at, zone), s.grid, s.level.cell(), s.dbm.cell(), s.technology, s.transport.name.lowercase(),
                flag(s.validated), s.probe.name.lowercase(), s.probeMs.cell(),
            )
        }
        return csv.toString()
    }

    fun deliveryCsv(rows: List<DeliveryRow>, zone: TimeZone): String {
        val csv = LogExport.Csv(DELIVERY_COLUMNS)
        for (r in rows) {
            csv.row(
                r.incidentId, flag(r.inReporterLog), flag(r.atConsole), time(r.createdAt, zone), time(r.consoleAt, zone),
                r.consoleHops.cell(), r.category.toString(), r.severity.toString(), r.grid, r.msToReceived.cell(),
                r.msToAcknowledged.cell(), r.firstAckHops.cell(), r.republished.cell(),
            )
        }
        return csv.toString()
    }

    fun json(
        role: Role,
        venue: String,
        grid: SiteGrid,
        reports: List<ReportTiming>,
        snapshots: List<MeshSnapshot>,
        network: List<NetworkSample>,
        delivery: List<DeliveryRow>,
        exportedAt: Long,
        zone: TimeZone,
    ): String = codec.encodeToString(
        MetricsDocument.serializer(),
        MetricsDocument(
            FORMAT, VERSION, LogExport.time(exportedAt, zone), role, venue, GridInfo(grid.columns, grid.rows, SiteGrid.CELL_METERS),
            reports.sortedBy { it.createdAt }, snapshots, network, delivery,
        ),
    )

    /** The reporter's records in a metrics export from another phone; null if [text] isn't one. */
    fun reportsIn(text: String): List<ReportTiming>? {
        if (text.length > MAX_IMPORT_BYTES) return null
        val document = runCatching { codec.decodeFromString(MetricsDocument.serializer(), text) }.getOrNull() ?: return null
        return document.reports.takeIf { document.format == FORMAT }
    }

    private fun byType(counts: Map<Int, Long>, known: Set<Int>): List<String> =
        TYPES.map { (counts[it.second] ?: 0L).toString() } + counts.filterKeys { it !in known }.values.sum().toString()

    private fun time(ms: Long?, zone: TimeZone): String = ms?.let { LogExport.time(it, zone) }.orEmpty()

    private fun rate(value: Double): String = formatDecimal(value, 1)

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun Number?.cell(): String = this?.toString().orEmpty()
}
