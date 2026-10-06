package ro.safetyplease.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.app.chat.ChatManager
import ro.safetyplease.app.data.Friend
import ro.safetyplease.app.data.JsonStore
import ro.safetyplease.app.data.Settings
import ro.safetyplease.app.data.StaffIncident
import ro.safetyplease.app.incidents.Clustering
import ro.safetyplease.app.incidents.StatusFilter
import ro.safetyplease.app.mesh.PowerMode
import ro.safetyplease.app.mesh.PowerPolicy
import ro.safetyplease.app.protocol.AckStatus
import ro.safetyplease.app.protocol.IncidentCategory
import ro.safetyplease.app.protocol.Severity
import ro.safetyplease.app.venue.GeoPoint
import ro.safetyplease.app.venue.Venue
import ro.safetyplease.app.venue.pointInPolygon
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SmallPartsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // --- point-in-polygon si venue ---

    private val square = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(10.0, 10.0), GeoPoint(10.0, 0.0))

    @Test
    fun pointInSquare() {
        assertTrue(pointInPolygon(5.0, 5.0, square))
        assertFalse(pointInPolygon(5.0, 11.0, square))
        assertFalse(pointInPolygon(-1.0, 5.0, square))
        assertFalse(pointInPolygon(5.0, 5.0, square.take(2)))
    }

    @Test
    fun pointInConcavePolygon() {
        // un "L": coltul din dreapta sus lipseste
        val l = listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(5.0, 10.0),
            GeoPoint(5.0, 5.0), GeoPoint(10.0, 5.0), GeoPoint(10.0, 0.0),
        )
        assertTrue(pointInPolygon(2.0, 8.0, l))
        assertTrue(pointInPolygon(8.0, 2.0, l))
        assertFalse(pointInPolygon(8.0, 8.0, l))
    }

    private fun bundledVenue(): Venue = Venue.parse(File("src/main/assets/venue.json").readText())

    @Test
    fun bundledVenueParsesAndZonesAreConsistent() {
        val venue = bundledVenue()
        assertTrue(venue.zones.size >= 6)
        assertEquals("id-uri unice", venue.zones.size, venue.zones.map { it.id }.toSet().size)
        for (zone in venue.zones) {
            assertTrue(zone.id, zone.id.toByteArray().size <= 32)
            assertTrue(zone.id, zone.polygon.size >= 3)
            assertTrue(zone.id, zone.color.matches(Regex("#[0-9A-Fa-f]{6}")))
            for (p in zone.polygon) assertTrue(zone.id, venue.bounds.contains(p.lat, p.lon))
            val c = zone.center
            assertEquals("centrul unei zone e in zona ei", zone.id, venue.zoneAt(c.lat, c.lon)?.id)
        }
        val meeting = venue.meetingPoint
        assertNotNull(meeting)
        assertTrue(venue.bounds.contains(meeting!!.lat, meeting.lon))
    }

    @Test
    fun zoneLookup() {
        val venue = bundledVenue()
        assertEquals("main-stage", venue.zoneAt(43.9505, 28.6350)?.id)
        assertEquals("food", venue.zoneAt(43.9494, 28.6329)?.id)
        assertNull("in afara zonelor", venue.zoneAt(43.9000, 28.6000))
        assertEquals("Scena principală", venue.zoneName("main-stage"))
        assertEquals("id necunoscut ramane ca atare", "zona-x", venue.zoneName("zona-x"))
    }

    // --- clustering ---

    private fun incident(id: String, category: Int, zone: String, atMin: Double, severity: Int = Severity.MEDIUM, status: Int = 0) =
        StaffIncident(
            incidentId = id, category = category, severity = severity, zone = zone,
            reportedAt = (atMin * 60_000).toLong(), receivedAt = (atMin * 60_000).toLong(), hops = 1, status = status,
        )

    @Test
    fun sameCategoryAndZoneWithinFiveMinutesIsOneCard() {
        val clusters = Clustering.cluster(
            listOf(
                incident("a", IncidentCategory.MEDICAL, "main-stage", 0.0),
                incident("b", IncidentCategory.MEDICAL, "main-stage", 2.0, severity = Severity.URGENT),
                incident("c", IncidentCategory.MEDICAL, "main-stage", 4.9),
                incident("d", IncidentCategory.MEDICAL, "main-stage", 9.0),
                incident("e", IncidentCategory.MEDICAL, "food", 1.0),
                incident("f", IncidentCategory.FIRE, "main-stage", 1.0),
            )
        )
        assertEquals(4, clusters.size)
        val big = clusters.first { it.count == 3 }
        assertEquals(setOf("a", "b", "c"), big.incidents.map { it.incidentId }.toSet())
        assertEquals(Severity.URGENT, big.severity)
        assertEquals("b", big.lead.incidentId)
        assertEquals("cel mai grav e primul", big, clusters.first())
    }

    @Test
    fun reportsWithoutZoneStayAlone() {
        val clusters = Clustering.cluster(
            listOf(incident("a", IncidentCategory.MEDICAL, "", 0.0), incident("b", IncidentCategory.MEDICAL, "", 1.0))
        )
        assertEquals(2, clusters.size)
    }

    @Test
    fun clustersSortBySeverityThenRecency() {
        val clusters = Clustering.cluster(
            listOf(
                incident("old-urgent", IncidentCategory.FIRE, "bar", 0.0, Severity.URGENT),
                incident("new-low", IncidentCategory.OTHER, "food", 50.0, Severity.LOW),
                incident("new-urgent", IncidentCategory.MEDICAL, "food", 40.0, Severity.URGENT),
                incident("medium", IncidentCategory.CROWD, "entrance", 45.0, Severity.MEDIUM),
            )
        )
        assertEquals(listOf("new-urgent", "old-urgent", "medium", "new-low"), clusters.map { it.lead.incidentId })
    }

    @Test
    fun clusterStatusFollowsLeastAdvancedReportAndFilters() {
        val cluster = Clustering.cluster(
            listOf(
                incident("a", IncidentCategory.MEDICAL, "bar", 0.0, status = AckStatus.RESOLVED),
                incident("b", IncidentCategory.MEDICAL, "bar", 1.0, status = AckStatus.RECEIVED),
            )
        ).single()
        assertEquals(AckStatus.RECEIVED, cluster.status)
        assertTrue(Clustering.matches(cluster, StatusFilter.OPEN))
        assertTrue(Clustering.matches(cluster, StatusFilter.ALL))
        assertFalse(Clustering.matches(cluster, StatusFilter.RESOLVED))
        assertFalse(Clustering.matches(cluster, StatusFilter.TAKEN))
    }

    @Test
    fun activeFilterKeepsNewAndTakenButNotClosed() {
        fun clusterWith(status: Int) = Clustering.cluster(listOf(incident("x", IncidentCategory.MEDICAL, "bar", 0.0, status = status))).single()
        assertTrue(Clustering.matches(clusterWith(AckStatus.RECEIVED), StatusFilter.ACTIVE))
        assertTrue(Clustering.matches(clusterWith(AckStatus.ACKNOWLEDGED), StatusFilter.ACTIVE))
        assertFalse(Clustering.matches(clusterWith(AckStatus.RESOLVED), StatusFilter.ACTIVE))
        assertFalse(Clustering.matches(clusterWith(AckStatus.CANCELLED), StatusFilter.ACTIVE))
    }

    // --- persistenta ---

    @Test
    fun storeSurvivesRestart() = runTest {
        val file = File(tmp.root, "settings.json")
        val first = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        first.update { it.copy(nickname = "Ana", onboarded = true, ignoredPrefixes = listOf(1, -2)) }
        advanceTimeBy(1_000)
        assertTrue(file.exists())
        val second = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        assertEquals("Ana", second.value.nickname)
        assertEquals(listOf(1, -2), second.value.ignoredPrefixes)
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun corruptFileFallsBackToDefaultAndIsReplaced() = runTest {
        val file = File(tmp.root, "friends.json").apply { writeText("{ nu e json") }
        val store = JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), backgroundScope)
        assertTrue(store.value.isEmpty())
        store.update { it + Friend(1L, "x", "00", "00", 5L) }
        store.flush()
        val again = JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), backgroundScope)
        assertEquals(1, again.value.size)
    }

    @Test
    fun resetClearsStateAndFile() = runTest {
        val file = File(tmp.root, "s.json")
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        store.update { it.copy(nickname = "x") }
        store.flush()
        store.reset()
        assertEquals("", store.value.nickname)
        assertFalse(file.exists())
    }

    // --- outbox si energie ---

    @Test
    fun outboxBackoffGrowsAndCaps() {
        assertEquals(0L, ChatManager.backoffMs(0))
        assertEquals(15_000L, ChatManager.backoffMs(1))
        assertEquals(120_000L, ChatManager.backoffMs(4))
        assertEquals(300_000L, ChatManager.backoffMs(5))
        assertEquals(300_000L, ChatManager.backoffMs(40))
    }

    @Test
    fun powerPolicyFollowsBatteryAndIncidents() {
        val normal = PowerPolicy.modes(batteryPercent = 80, charging = false, pendingIncident = false, boost = false)
        assertEquals(PowerMode.BALANCED to PowerMode.BALANCED, normal)
        val low = PowerPolicy.modes(batteryPercent = 15, charging = false, pendingIncident = false, boost = false)
        assertEquals(PowerMode.LOW_POWER to PowerMode.LOW_POWER, low)
        val lowButCharging = PowerPolicy.modes(batteryPercent = 15, charging = true, pendingIncident = false, boost = false)
        assertEquals(PowerMode.BALANCED to PowerMode.BALANCED, lowButCharging)
        val boosted = PowerPolicy.modes(batteryPercent = 15, charging = false, pendingIncident = true, boost = true)
        assertEquals("15 s dupa un incident, chiar si cu baterie putina", PowerMode.LOW_LATENCY to PowerMode.LOW_LATENCY, boosted)
        val pending = PowerPolicy.modes(batteryPercent = 80, charging = false, pendingIncident = true, boost = false)
        assertEquals("advertising rapid cat timp raportul nu a ajuns", PowerMode.BALANCED to PowerMode.LOW_LATENCY, pending)
        val pendingLow = PowerPolicy.modes(batteryPercent = 10, charging = false, pendingIncident = true, boost = false)
        assertEquals(PowerMode.LOW_POWER to PowerMode.LOW_LATENCY, pendingLow)
    }
}
