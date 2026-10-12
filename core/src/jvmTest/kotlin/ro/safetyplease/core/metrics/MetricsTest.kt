package ro.safetyplease.core.metrics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.Role
import ro.safetyplease.core.mesh.DropReason
import ro.safetyplease.core.mesh.LinkInfo
import ro.safetyplease.core.mesh.MeshCounters
import ro.safetyplease.core.mesh.MeshState
import ro.safetyplease.core.mesh.TestClock
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.SiteGrid
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MetricsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val grid = SiteGrid.of(Bounds(43.948, 28.632, 43.9512, 28.6382))
    private val t0 = 1_791_832_500_000L

    private val report = MyReport(
        incidentId = "0a".repeat(16), category = IncidentCategory.MEDICAL, severity = Severity.URGENT, zone = "main-stage",
        lat = 43.9505, lon = 28.635, description = "A leșinat", anonymous = false, createdAt = t0, teamName = "Medical 1",
    )

    // --- the reporter's round trip ---

    @Test
    fun firstAckOfAnyStatusIsTheReceiptWithItsHops() {
        val r = report.timedAck(AckStatus.RECEIVED, 3, t0 + 4_000)
        assertEquals(t0 + 4_000, r.receivedAt)
        assertEquals(3, r.firstAckHops)
        assertEquals(0L, r.acknowledgedAt)
    }

    @Test
    fun aPersonHasItFromTheFirstTakeOrResolve() {
        val received = report.timedAck(AckStatus.RECEIVED, 3, t0 + 4_000)
        val taken = received.timedAck(AckStatus.ON_SCENE, 1, t0 + 90_000)
        assertEquals(t0 + 4_000, taken.receivedAt)
        assertEquals("the first ACK's hops", 3, taken.firstAckHops)
        assertEquals(t0 + 90_000, taken.acknowledgedAt)
        assertSame("later ACKs change nothing", taken, taken.timedAck(AckStatus.RESOLVED, 2, t0 + 200_000))

        val resolvedFirst = report.timedAck(AckStatus.RESOLVED, 2, t0 + 50_000)
        assertEquals(t0 + 50_000, resolvedFirst.receivedAt)
        assertEquals(t0 + 50_000, resolvedFirst.acknowledgedAt)
    }

    @Test
    fun closingWithoutAPersonIsNotAnAcknowledgement() {
        for (status in listOf(AckStatus.CANCELLED, AckStatus.UNFOUNDED, AckStatus.CLOSED_BY_CONTROL, AckStatus.REOPENED)) {
            val r = report.timedAck(status, 2, t0 + 10_000)
            assertEquals(t0 + 10_000, r.receivedAt)
            assertEquals(0L, r.acknowledgedAt)
        }
    }

    @Test
    fun timingKeepsOnlyWhatIsAnonymous() {
        val r = report.copy(receivedAt = t0 + 4_000, acknowledgedAt = t0 + 90_000, firstAckHops = 3, republished = 1, sent = true)
        val timing = ReportTiming.of(r, grid)
        assertEquals(4_000L, timing.msToReceived)
        assertEquals(90_000L, timing.msToAcknowledged)
        assertEquals(3, timing.firstAckHops)
        assertEquals(1, timing.republished)
        assertEquals(grid.cellAt(43.9505, 28.635), timing.grid)

        val waiting = ReportTiming.of(report.copy(lat = null, lon = null), grid)
        assertNull(waiting.msToReceived)
        assertNull(waiting.msToAcknowledged)
        assertNull("no ACK, no hops", waiting.firstAckHops)
        assertEquals("", waiting.grid)
    }

    // --- mesh snapshots ---

    private fun state(scale: Long, links: Int = 2) = MeshState(
        links = (1..links).map { LinkInfo(it, "A$it", it.toLong(), null, 0, true, 0) },
        sent = 10 * scale, received = 20 * scale, relayed = 5 * scale, dropped = 3 * scale, queueFull = scale,
        cachedReports = links,
        counters = MeshCounters(
            sentByType = mapOf(PacketType.INCIDENT_REPORT to 2 * scale, PacketType.PRIVATE to 8 * scale),
            receivedByType = mapOf(PacketType.HELLO to scale),
            drops = mapOf(DropReason.DUPLICATE to 2 * scale, DropReason.RATE_LIMIT to scale),
            storeAndForward = scale, refusedBanned = 0,
            reportHops = mapOf(1 to scale, 3 to 2 * scale), ackHops = mapOf(2 to scale),
            linksOpened = 4 * scale, linksClosed = 2 * scale,
        ),
    )

    @Test
    fun snapshotHoldsWhatHappenedInItsPeriod() {
        val s = MeshSnapshot.between(state(1), state(3, links = 4), t0, 300_000, Role.STAFF)
        assertEquals(20L, s.sent)
        assertEquals(40L, s.received)
        assertEquals(6L, s.dropped)
        assertEquals("links as they are at the end", 4, s.links)
        assertEquals(mapOf(PacketType.INCIDENT_REPORT to 4L, PacketType.PRIVATE to 16L), s.sentByType)
        assertEquals(mapOf(DropReason.DUPLICATE to 4L, DropReason.RATE_LIMIT to 2L), s.drops)
        assertEquals("the hop histogram", mapOf(1 to 2L, 3 to 4L), s.reportHops)
        assertEquals(8L, s.linksOpened)
        assertEquals(4L, s.linksClosed)
        assertEquals(96.0, s.perHour(s.linksOpened), 0.0)
    }

    @Test
    fun snapshotLeavesOutWhatDidntChange() {
        val s = MeshSnapshot.between(state(2), state(2), t0, 300_000, Role.ANCHOR)
        assertEquals(0L, s.sent)
        assertEquals(emptyMap<DropReason, Long>(), s.drops)
        assertEquals(emptyMap<Int, Long>(), s.reportHops)
        assertEquals(0.0, MeshSnapshot.between(state(1), state(2), t0, 0, Role.ANCHOR).perHour(5), 0.0)
    }

    @Test
    fun metricsLogKeepsTheNewestSnapshots() {
        var log = MetricsLog()
        repeat(MetricsLog.MAX_SNAPSHOTS + 5) { log = log.with(MeshSnapshot(t0 + it, 1, Role.PARTICIPANT, 0, 0, 0)) }
        assertEquals(MetricsLog.MAX_SNAPSHOTS, log.snapshots.size)
        assertEquals(t0 + 5, log.snapshots.first().at)
        assertEquals(t0 + MetricsLog.MAX_SNAPSHOTS + 4, log.snapshots.last().at)
    }

    @Test
    fun networkLogKeepsTheNewestSamples() {
        var log = NetworkLog()
        repeat(NetworkLog.MAX_SAMPLES + 3) { log = log.with(NetworkSample(t0 + it)) }
        assertEquals(NetworkLog.MAX_SAMPLES, log.samples.size)
        assertEquals(t0 + 3, log.samples.first().at)
    }

    @Test
    fun meshMetricsAppendsDifferencesSinceTheLastSnapshot() = runTest {
        val clock = TestClock(testScheduler)
        val store = JsonStore(File(tmp.root, "metrics.json"), MetricsLog.serializer(), MetricsLog(), backgroundScope, MetricsLog.SCHEMA_VERSION)
        val metrics = MeshMetrics(store, clock)
        advanceTimeBy(MeshMetrics.INTERVAL_MS)
        metrics.snapshot(state(1), Role.STAFF)
        advanceTimeBy(MeshMetrics.INTERVAL_MS)
        metrics.snapshot(state(3), Role.STAFF)
        val (first, second) = store.value.snapshots
        assertEquals("from zero at start", 10L, first.sent)
        assertEquals(20L, second.sent)
        assertEquals(MeshMetrics.INTERVAL_MS, second.periodMs)
        assertEquals(clock.wallMs(), second.at)

        advanceTimeBy(1_000)
        store.flush()
        val reloaded = JsonStore(File(tmp.root, "metrics.json"), MetricsLog.serializer(), MetricsLog(), backgroundScope, MetricsLog.SCHEMA_VERSION)
        assertEquals("maps keyed by type, reason and hops survive the file", store.value, reloaded.value)
    }
}
