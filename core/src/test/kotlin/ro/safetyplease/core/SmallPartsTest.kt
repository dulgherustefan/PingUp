package ro.safetyplease.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ro.safetyplease.core.chat.ChatManager
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.JsonStore
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Clustering
import ro.safetyplease.core.incidents.StatusFilter
import ro.safetyplease.core.mesh.PowerMode
import ro.safetyplease.core.mesh.PowerPolicy
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.compassSector
import ro.safetyplease.core.venue.pointInPolygon
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SmallPartsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // --- point-in-polygon and venue ---

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
        // an "L": the top right corner is missing
        val l = listOf(
            GeoPoint(0.0, 0.0), GeoPoint(0.0, 10.0), GeoPoint(5.0, 10.0),
            GeoPoint(5.0, 5.0), GeoPoint(10.0, 5.0), GeoPoint(10.0, 0.0),
        )
        assertTrue(pointInPolygon(2.0, 8.0, l))
        assertTrue(pointInPolygon(8.0, 2.0, l))
        assertFalse(pointInPolygon(8.0, 8.0, l))
    }

    private fun bundledVenue(): Venue = Venue.parse(File("../app/src/main/assets/venue.json").readText())

    @Test
    fun bundledVenueParsesAndZonesAreConsistent() {
        val venue = bundledVenue()
        assertTrue(venue.zones.size >= 6)
        assertEquals("unique ids", venue.zones.size, venue.zones.map { it.id }.toSet().size)
        for (zone in venue.zones) {
            assertTrue(zone.id, zone.id.toByteArray().size <= 32)
            assertTrue(zone.id, zone.polygon.size >= 3)
            assertTrue(zone.id, zone.color.matches(Regex("#[0-9A-Fa-f]{6}")))
            for (p in zone.polygon) assertTrue(zone.id, venue.bounds.contains(p.lat, p.lon))
            val c = zone.center
            assertEquals("a zone's center is inside it", zone.id, venue.zoneAt(c.lat, c.lon)?.id)
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
        assertNull("outside every zone", venue.zoneAt(43.9000, 28.6000))
        assertEquals("Scena principală", venue.zoneName("main-stage"))
        assertEquals("unknown id stays as is", "zona-x", venue.zoneName("zona-x"))
    }

    @Test
    fun bundledVenuePlacesAreInsideAndTheNearestWins() {
        val venue = bundledVenue()
        assertTrue(venue.zones.all { it.type.isNotEmpty() })
        for (poi in venue.pois) assertTrue(poi.name, venue.bounds.contains(poi.lat, poi.lon))
        for (path in venue.paths) for (p in path) assertTrue(venue.bounds.contains(p.lat, p.lon))
        assertEquals("Poarta principală", venue.nearest("exit", GeoPoint(43.9482, 28.6343))?.name)
        assertEquals("Ieșire est", venue.nearest("exit", GeoPoint(43.9493, 28.6378))?.name)
        assertEquals("the medical zone counts as a medical place", "Punct medical", venue.nearest("medical", GeoPoint(43.9484, 28.6360))?.name)
        assertEquals(venue.meetingPoint, venue.nearest(Venue.MEETING, null)?.point)
        assertNull(venue.nearest("teleport", GeoPoint(43.949, 28.635)))
    }

    @Test
    fun compassSectors() {
        val o = GeoPoint(43.949, 28.635)
        assertEquals(0, compassSector(o, GeoPoint(43.950, 28.635)))
        assertEquals(1, compassSector(o, GeoPoint(43.950, 28.6364)))
        assertEquals(2, compassSector(o, GeoPoint(43.949, 28.637)))
        assertEquals(4, compassSector(o, GeoPoint(43.948, 28.635)))
        assertEquals(6, compassSector(o, GeoPoint(43.949, 28.633)))
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
        assertEquals("most severe first", big, clusters.first())
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

    // --- persistence ---

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
    fun fileFromBeforeVersioningLoadsAsVersionOne() = runTest {
        val file = File(tmp.root, "settings.json").apply { writeText("""{"nickname":"Ana","onboarded":true}""") }
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        assertFalse(store.unreadable)
        assertEquals("Ana", store.value.nickname)
        assertTrue(store.value.onboarded)
        store.update { it.copy(teamName = "Medical 1") }
        store.flush()
        val saved = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(1, saved.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("Ana", saved.getValue("data").jsonObject.getValue("nickname").jsonPrimitive.content)
        assertEquals("Ana", JsonStore(file, Settings.serializer(), Settings(), backgroundScope).value.nickname)
    }

    @Test
    fun manualZoneSurvivesRestartAndOlderFilesLoadWithoutIt() = runTest {
        val file = File(tmp.root, "settings.json").apply { writeText("""{"nickname":"Ana","onboarded":true}""") }
        val first = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        assertEquals("Ana", first.value.nickname)
        assertEquals("", first.value.manualZone)
        first.update { it.copy(manualZone = "camping") }
        first.flush()
        val second = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        assertEquals("camping", second.value.manualZone)
        assertEquals("Ana", second.value.nickname)
    }

    @Test
    fun olderVersionIsMigratedOnLoad() = runTest {
        // a made-up version 2 that renamed "nick" to "nickname"; the file predates versioning
        val file = File(tmp.root, "settings.json").apply { writeText("""{"nick":"Ana"}""") }
        val migratedFrom = mutableListOf<Int>()
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope, schemaVersion = 2) { version, data ->
            migratedFrom += version
            val old = data.jsonObject
            JsonObject(old - "nick" + ("nickname" to old.getValue("nick")))
        }
        assertEquals(listOf(1), migratedFrom)
        assertEquals("Ana", store.value.nickname)
        store.flush()
        assertEquals(2, Json.parseToJsonElement(file.readText()).jsonObject.getValue("schemaVersion").jsonPrimitive.int)
    }

    @Test
    fun everyWriteKeepsThePreviousFileAsBackup() = runTest {
        val file = File(tmp.root, "friends.json")
        val backup = File(file.path + ".bak")
        val store = JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), backgroundScope)
        store.update { it + Friend(1L, "Ana", "00", "00", 5L) }
        store.flush()
        assertFalse("nothing to keep before the first write", backup.exists())
        val first = file.readText()
        store.update { it + Friend(2L, "Bogdan", "00", "00", 6L) }
        advanceTimeBy(1_000)
        assertEquals(first, backup.readText())
        assertEquals(2, JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), backgroundScope).value.size)
    }

    @Test
    fun unreadableFileIsKeptAndNeverWrittenOver() = runTest {
        val file = File(tmp.root, "friends.json").apply { writeText("{ nu e json") }
        val store = JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), backgroundScope)
        assertTrue(store.unreadable)
        assertTrue(store.value.isEmpty())
        store.update { it + Friend(1L, "x", "00", "00", 5L) }
        advanceTimeBy(1_000)
        store.flush()
        assertEquals("changes stay in memory", 1, store.value.size)
        assertEquals("{ nu e json", file.readText())
        assertFalse(File(file.path + ".bak").exists())
        assertTrue(JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), backgroundScope).unreadable)

        store.reset()
        assertFalse("an explicit reset is the way out", store.unreadable)
        store.update { it + Friend(2L, "y", "00", "00", 6L) }
        store.flush()
        assertEquals(1, JsonStore(file, ListSerializer(Friend.serializer()), emptyList(), backgroundScope).value.size)
    }

    @Test
    fun fileFromANewerVersionIsNotWrittenOver() = runTest {
        val newer = """{"schemaVersion":2,"data":{"nickname":"Ana","pronouns":"ea"}}"""
        val file = File(tmp.root, "settings.json").apply { writeText(newer) }
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        assertTrue(store.unreadable)
        assertEquals("", store.value.nickname)
        store.update { it.copy(nickname = "Bogdan") }
        store.flush()
        assertEquals(newer, file.readText())
    }

    @Test
    fun resetClearsStateFileAndBackup() = runTest {
        val file = File(tmp.root, "s.json")
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        store.update { it.copy(nickname = "x") }
        store.flush()
        store.update { it.copy(nickname = "y") }
        store.flush()
        assertTrue(File(file.path + ".bak").exists())
        store.reset()
        assertEquals("", store.value.nickname)
        assertFalse(file.exists())
        assertFalse(File(file.path + ".bak").exists())
    }

    // --- outbox and power ---

    @Test
    fun outboxBackoffGrowsAndCaps() {
        assertEquals(0L, ChatManager.backoffMs(0))
        assertEquals(15_000L, ChatManager.backoffMs(1))
        assertEquals(120_000L, ChatManager.backoffMs(4))
        assertEquals(300_000L, ChatManager.backoffMs(5))
        assertEquals(300_000L, ChatManager.backoffMs(40))
    }

    @Test
    fun powerPolicyFollowsBatteryAndOnlyScanningSpeedsUpAfterAReport() {
        val normal = PowerPolicy.modes(batteryPercent = 80, charging = false, boost = false)
        assertEquals(PowerMode.BALANCED to PowerMode.BALANCED, normal)
        val low = PowerPolicy.modes(batteryPercent = 15, charging = false, boost = false)
        assertEquals(PowerMode.LOW_POWER to PowerMode.LOW_POWER, low)
        val lowButCharging = PowerPolicy.modes(batteryPercent = 15, charging = true, boost = false)
        assertEquals(PowerMode.BALANCED to PowerMode.BALANCED, lowButCharging)
        val boosted = PowerPolicy.modes(batteryPercent = 80, charging = false, boost = true)
        assertEquals("advertising keeps its pace, so the reporter doesn't stand out", PowerMode.LOW_LATENCY to PowerMode.BALANCED, boosted)
        val boostedLow = PowerPolicy.modes(batteryPercent = 15, charging = false, boost = true)
        assertEquals("15 s after a report, even on low battery", PowerMode.LOW_LATENCY to PowerMode.LOW_POWER, boostedLow)
    }
}
