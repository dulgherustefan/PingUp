package ro.safetyplease.app.demo

import ro.safetyplease.core.control.HeardPeer
import ro.safetyplease.core.control.PointBoard
import ro.safetyplease.core.control.PointReport
import ro.safetyplease.core.data.StaffIncident
import ro.safetyplease.core.incidents.Recognition
import ro.safetyplease.core.protocol.AckStatus
import ro.safetyplease.core.protocol.Answer
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.MobileReading
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.PointLevel
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.Venue
import kotlin.math.cos

/**
 * The "scenariu" of demo mode: a shift's worth of test data for the console, for screenshots and rehearsals. Every
 * incident id starts with [ID_PREFIX] and every description with [MARK], so the data reads as a test and [isDemo]
 * finds it again; the anchors advertise prefixes no phone has ([ANCHOR_PREFIXES]).
 */
object DemoScenario {
    const val ID_PREFIX = "dec0de"
    const val MARK = "Demo"
    val ANCHOR_PREFIXES = listOf(0x7E570001, 0x7E570002)

    fun isDemo(incidentId: String): Boolean = incidentId.startsWith(ID_PREFIX)

    private class Spec(
        val category: Int,
        val severity: Int,
        val zone: String,
        /** Metres from the zone's centre, east and north. */
        val east: Int,
        val north: Int,
        val minutesAgo: Int,
        val text: String,
        val hint: String = "",
        val status: Int = AckStatus.RECEIVED,
        val team: String = "",
        val assigned: Boolean = false,
        val responds: Int = Answer.UNASKED,
        val breathes: Int = Answer.UNASKED,
        val cancelRequested: Boolean = false,
        val staffTeam: String = "",
        val people: Int = 0,
        val danger: Boolean = false,
    )

    private val specs = listOf(
        Spec(
            IncidentCategory.MEDICAL, Severity.URGENT, "main-stage", -15, 10, 3, "persoană căzută lângă bariera din față, nu răspunde",
            hint = "tricou roșu, steag galben", responds = Answer.NO, breathes = Answer.YES,
        ),
        Spec(
            IncidentCategory.MEDICAL, Severity.URGENT, "second-stage", 10, -5, 9, "leșin în mulțime",
            hint = "lanternă de telefon aprinsă", status = AckStatus.ACKNOWLEDGED, team = "Medical 1", assigned = true,
            responds = Answer.YES, breathes = Answer.YES,
        ),
        Spec(
            IncidentCategory.MEDICAL, Severity.MEDIUM, "food", 5, 5, 18, "amețeală, probabil de la căldură",
            status = AckStatus.ON_SCENE, team = "Medical 2", responds = Answer.YES, breathes = Answer.YES,
        ),
        Spec(
            IncidentCategory.MEDICAL, Severity.URGENT, "camping", -20, 0, 34, "tăietură adâncă la mână",
            hint = "cort verde, lângă stâlpul 3", status = AckStatus.HANDED_OVER, team = "Medical 1",
            responds = Answer.YES, breathes = Answer.YES,
        ),
        Spec(IncidentCategory.VIOLENCE, Severity.URGENT, "bar", 20, -10, 2, "bătaie între doi tineri", hint = "lângă boxa mare"),
        Spec(
            IncidentCategory.HARASSMENT, Severity.MEDIUM, "chill", 0, 15, 14, "cineva insistă și nu pleacă",
            hint = "rochie albă, lângă hamace", status = AckStatus.ACKNOWLEDGED, team = "Securitate 1",
        ),
        Spec(
            IncidentCategory.LOST_CHILD, Severity.URGENT, "entrance", 10, 0, 6, "băiat de 6 ani, s-a pierdut de părinți",
            hint = "șapcă albastră", status = AckStatus.ACKNOWLEDGED, team = "Securitate", assigned = true,
        ),
        Spec(
            IncidentCategory.CROWD, Severity.MEDIUM, "main-stage", 25, -20, 11, "presiune mare la bariera din stânga",
            status = AckStatus.ON_SCENE, team = "Stewarzi 1",
        ),
        Spec(
            IncidentCategory.FIRE, Severity.LOW, "camping", 15, 15, 52, "grătar lăsat aprins între corturi",
            status = AckStatus.CLOSED_BY_CONTROL, team = "Control",
        ),
        Spec(IncidentCategory.OTHER, Severity.LOW, "food", -10, -5, 41, "geantă uitată pe o masă", status = AckStatus.UNFOUNDED, team = "Control"),
        Spec(IncidentCategory.MEDICAL, Severity.LOW, "chill", -15, -10, 66, "înțepătură de viespe", status = AckStatus.RESOLVED, team = "Medical 2"),
        Spec(
            IncidentCategory.LOST_FRIEND, Severity.LOW, "second-stage", -10, 10, 7, "nu-mi găsesc prietena, are telefonul închis",
            hint = "geacă galbenă", cancelRequested = true,
        ),
        Spec(
            IncidentCategory.MEDICAL, Severity.URGENT, "bar", -25, 5, 5, "trei persoane intoxicate, una vomită",
            status = AckStatus.ON_SCENE, team = "Securitate 2", staffTeam = "Securitate 2", people = 3, danger = true,
            responds = Answer.YES, breathes = Answer.UNKNOWN,
        ),
    )

    /** Oldest first, as the staff list keeps them. */
    fun incidents(venue: Venue, nowMs: Long): List<StaffIncident> = specs.mapIndexed { i, s ->
        val bounds = venue.bounds
        val center = venue.zone(s.zone)?.center ?: GeoPoint((bounds.minLat + bounds.maxLat) / 2, (bounds.minLon + bounds.maxLon) / 2)
        val at = offset(center, s.east, s.north)
        val reportedAt = nowMs - s.minutesAgo * 60_000L
        val receivedAt = reportedAt + (2 + i % 5) * 1_000L
        val statusAt = if (s.status == AckStatus.RECEIVED) receivedAt else receivedAt + (s.minutesAgo / 2).coerceAtLeast(1) * 60_000L
        StaffIncident(
            incidentId = ID_PREFIX + i.toString(16).padStart(32 - ID_PREFIX.length, '0'),
            category = s.category,
            severity = s.severity,
            zone = s.zone,
            lat = at.lat,
            lon = at.lon,
            description = Recognition.join(s.hint, "$MARK: ${s.text}"),
            nickname = s.staffTeam.ifEmpty { null },
            reportedAt = reportedAt,
            receivedAt = receivedAt,
            hops = 1 + i % 4,
            status = s.status,
            teamName = s.team,
            assigned = s.assigned,
            statusAt = statusAt,
            cancelRequested = s.cancelRequested,
            responds = s.responds,
            breathes = s.breathes,
            fromStaff = s.staffTeam.isNotEmpty(),
            people = s.people,
            dangerContinues = s.danger,
            accuracyM = 8 + i % 3 * 7,
            landmark = venue.nearestLandmark(at)?.number ?: 0,
        )
    }.sortedBy { it.receivedAt }

    /** One status per point of the venue, free, busy and closed in turn; the oldest is still fresh. */
    fun points(venue: Venue, nowMs: Long): Map<Int, PointReport> = PointBoard.points(venue).mapIndexed { i, poi ->
        val level = listOf(PointLevel.FREE, PointLevel.BUSY, PointLevel.FREE, PointLevel.CLOSED)[i % 4]
        val at = nowMs - (2 + i * 2) * 60_000L
        poi.number to PointReport(poi.number, level, poi.team.ifEmpty { "Stewarzi 1" }, at, at / 1000)
    }.toMap()

    /** Two anchors on duty, with their heartbeats: one where mobile data fails, one where it works. */
    fun anchors(venue: Venue, grid: SiteGrid, nowMs: Long): Map<Int, HeardPeer> {
        val sites = listOf(
            Triple("main-stage", 3, MobileReading(1, 5, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_FAILED, false)),
            Triple("camping", 1, MobileReading(3, 5, MobileReading.TRANSPORT_CELLULAR, MobileReading.PROBE_OK, true)),
        )
        return sites.mapIndexedNotNull { i, (zone, links, mobile) ->
            val center = venue.zone(zone)?.center ?: return@mapIndexedNotNull null
            val square = grid.squareAt(center.lat, center.lon)
            val beat = Heartbeat(
                batteryPercent = 82 - i * 27, charging = i == 1, temperatureC = 33 + i * 4, quietS = 4 + i * 20,
                links = links, crowd = 6 - i * 3, zone = zone, mobile = mobile, column = square?.first, row = square?.second,
            )
            val prefix = ANCHOR_PREFIXES[i]
            prefix to HeardPeer(prefix, NodeFlags.ANCHOR, false, nowMs - 20_000L, true, beat, nowMs - (30 + i * 40) * 1_000L)
        }.toMap()
    }

    private fun offset(from: GeoPoint, east: Int, north: Int): GeoPoint {
        val metersPerDegree = 111_320.0
        return GeoPoint(from.lat + north / metersPerDegree, from.lon + east / (metersPerDegree * cos(Math.toRadians(from.lat))))
    }
}
