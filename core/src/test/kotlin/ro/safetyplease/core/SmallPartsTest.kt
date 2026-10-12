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
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.data.Settings
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Clustering
import ro.safetyplease.core.incidents.IncidentManager
import ro.safetyplease.core.incidents.StatusFilter
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

    // --- possible duplicates ---

    private fun incident(id: String, category: Int, zone: String, atMin: Double, severity: Int = Severity.MEDIUM, status: Int = 0) =
        StaffIncident(
            incidentId = id, category = category, severity = severity, zone = zone,
            reportedAt = (atMin * 60_000).toLong(), receivedAt = (atMin * 60_000).toLong(), hops = 1, status = status,
        )

    @Test
    fun twoMedicalCasesInTheSameZoneAMinuteApartAreTwoIncidents() {
        val first = incident("a", IncidentCategory.MEDICAL, "main-stage", 0.0, Severity.URGENT)
        val second = incident("b", IncidentCategory.MEDICAL, "main-stage", 1.0, Severity.URGENT)
        val hints = Clustering.suggestions(listOf(first, second))

        val hint = hints.getValue("a")
        assertTrue("both rows carry the same hint", hint === hints["b"])
        assertEquals(listOf("a", "b"), hint.incidentIds)
        assertFalse("two patients are never called one incident", hint.sameEvent)

        val afterTake = listOf(first.copy(status = AckStatus.ACKNOWLEDGED), second)
        assertEquals("taking one leaves the other unassigned", listOf("b"), afterTake.filter(StatusFilter.OPEN::matches).map { it.incidentId })
    }

    @Test
    fun violenceAndLostPeopleAreNeverOneIncidentEither() {
        for (category in listOf(IncidentCategory.VIOLENCE, IncidentCategory.LOST_CHILD, IncidentCategory.LOST_FRIEND)) {
            val hint = Clustering.suggestions(listOf(incident("a", category, "bar", 0.0), incident("b", category, "bar", 2.0))).getValue("b")
            assertEquals(2, hint.count)
            assertFalse(hint.sameEvent)
        }
    }

    @Test
    fun crowdAndFireReportsStillGroupWithinFiveMinutesOfTheFirst() {
        val hints = Clustering.suggestions(
            listOf(
                incident("c", IncidentCategory.CROWD, "main-stage", 4.9),
                incident("a", IncidentCategory.CROWD, "main-stage", 0.0),
                incident("b", IncidentCategory.CROWD, "main-stage", 2.0, severity = Severity.URGENT),
                incident("d", IncidentCategory.CROWD, "main-stage", 9.0),
                incident("e", IncidentCategory.CROWD, "food", 1.0),
                incident("f", IncidentCategory.FIRE, "main-stage", 1.0),
                incident("g", IncidentCategory.FIRE, "main-stage", 3.0),
            )
        )
        val crowd = hints.getValue("a")
        assertEquals("oldest first", listOf("a", "b", "c"), crowd.incidentIds)
        assertTrue(crowd.sameEvent)
        assertTrue(hints["b"] === crowd && hints["c"] === crowd)
        assertNull("more than five minutes after the first", hints["d"])
        assertNull("another zone", hints["e"])
        assertEquals("another category is another hint", listOf("f", "g"), hints.getValue("g").incidentIds)
        assertTrue(hints.getValue("g").sameEvent)
    }

    @Test
    fun closedReportsNeitherGetNorCountInAHint() {
        val hints = Clustering.suggestions(
            listOf(
                incident("open", IncidentCategory.CROWD, "bar", 0.0),
                incident("resolved", IncidentCategory.CROWD, "bar", 1.0, status = AckStatus.RESOLVED),
                incident("cancelled", IncidentCategory.CROWD, "bar", 2.0, status = AckStatus.CANCELLED),
            )
        )
        assertTrue("an open report next to closed ones stands alone", hints.isEmpty())

        val withTaken = Clustering.suggestions(
            listOf(
                incident("resolved", IncidentCategory.FIRE, "food", 0.0, status = AckStatus.RESOLVED),
                incident("taken", IncidentCategory.FIRE, "food", 1.0, status = AckStatus.ACKNOWLEDGED),
                incident("new", IncidentCategory.FIRE, "food", 2.0),
            )
        )
        assertEquals("a taken report is still work and counts", listOf("taken", "new"), withTaken.getValue("new").incidentIds)
        assertNull(withTaken["resolved"])
    }

    @Test
    fun reportsWithoutZoneGetNoHint() {
        val hints = Clustering.suggestions(listOf(incident("a", IncidentCategory.CROWD, "", 0.0), incident("b", IncidentCategory.CROWD, "", 1.0)))
        assertTrue(hints.isEmpty())
    }

    @Test
    fun filtersLookAtEachReportOnItsOwn() {
        fun with(status: Int) = incident("x", IncidentCategory.MEDICAL, "bar", 0.0, status = status)
        assertTrue(StatusFilter.OPEN.matches(with(AckStatus.RECEIVED)))
        assertFalse(StatusFilter.OPEN.matches(with(AckStatus.ACKNOWLEDGED)))
        assertTrue(StatusFilter.TAKEN.matches(with(AckStatus.ACKNOWLEDGED)))
        assertTrue(StatusFilter.ACTIVE.matches(with(AckStatus.RECEIVED)))
        assertTrue(StatusFilter.ACTIVE.matches(with(AckStatus.ACKNOWLEDGED)))
        assertFalse(StatusFilter.ACTIVE.matches(with(AckStatus.RESOLVED)))
        assertFalse(StatusFilter.ACTIVE.matches(with(AckStatus.CANCELLED)))
        assertTrue(StatusFilter.RESOLVED.matches(with(AckStatus.CANCELLED)))
        for (status in listOf(AckStatus.ON_SCENE, AckStatus.HANDED_OVER)) {
            assertTrue(StatusFilter.TAKEN.matches(with(status)) && StatusFilter.ACTIVE.matches(with(status)))
        }
        for (status in listOf(AckStatus.CLOSED_BY_CONTROL, AckStatus.UNFOUNDED)) assertTrue(StatusFilter.RESOLVED.matches(with(status)))
        assertTrue("a reopened incident waits for a team again", StatusFilter.OPEN.matches(with(AckStatus.REOPENED)))
        assertFalse(StatusFilter.RESOLVED.matches(with(AckStatus.ACKNOWLEDGED)))
        assertTrue(StatusFilter.ALL.matches(with(AckStatus.NONE)))
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

    @Test
    fun aWriteStillPendingAtResetDoesNotBringTheFileBack() = runTest {
        val file = File(tmp.root, "s.json")
        val store = JsonStore(file, Settings.serializer(), Settings(), backgroundScope)
        store.update { it.copy(nickname = "x") }
        store.reset()
        advanceTimeBy(1_000)
        store.flush()
        assertFalse(file.exists())
        store.update { it.copy(nickname = "y") }
        advanceTimeBy(1_000)
        assertEquals("y", JsonStore(file, Settings.serializer(), Settings(), backgroundScope).value.nickname)
    }

    // --- outbox ---

    @Test
    fun outboxBackoffGrowsAndCaps() {
        assertEquals(0L, ChatManager.backoffMs(0))
        assertEquals(15_000L, ChatManager.backoffMs(1))
        assertEquals(120_000L, ChatManager.backoffMs(4))
        assertEquals(300_000L, ChatManager.backoffMs(5))
        assertEquals(300_000L, ChatManager.backoffMs(40))
    }

    // --- own reports ---

    @Test
    fun pendingLastsUntilTheNewestReportWaitingForStaffTimesOut() {
        fun report(id: String, at: Long, status: Int = AckStatus.NONE, cancelled: Boolean = false) =
            MyReport(id, category = 1, severity = 1, zone = "", createdAt = at, status = status, cancelled = cancelled)
        val pending = IncidentManager.PENDING_MS
        assertNull(IncidentManager.pendingUntil(emptyList(), 0))
        val mine = listOf(
            report("a", at = 0),
            report("b", at = 60_000),
            report("c", at = 120_000, status = AckStatus.RECEIVED),
            report("d", at = 180_000, cancelled = true),
        )
        assertEquals(60_000 + pending, IncidentManager.pendingUntil(mine, 120_000))
        assertEquals(60_000 + pending, IncidentManager.pendingUntil(mine, pending + 1))
        assertNull(IncidentManager.pendingUntil(mine, 60_000 + pending))
    }
}
