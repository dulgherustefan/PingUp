package ro.safetyplease.core.position

import ro.safetyplease.core.data.OutgoingShare
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.NextUpdate
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendFindingTest {
    private val min = 60_000L
    private val here = GeoPoint(43.9500, 28.6350)

    /** [meters] north of [here]. */
    private fun north(meters: Double) = GeoPoint(here.lat + meters / 111_320.0, here.lon)

    @Test
    fun ageBucketsFollowThePlan() {
        assertEquals(FriendAge.NOW, FriendAge.of(0))
        assertEquals(FriendAge.NOW, FriendAge.of(2 * min - 1))
        assertEquals(FriendAge.RECENT, FriendAge.of(2 * min))
        assertEquals(FriendAge.RECENT, FriendAge.of(10 * min - 1))
        assertEquals(FriendAge.FADED, FriendAge.of(10 * min))
        assertEquals(FriendAge.FADED, FriendAge.of(30 * min - 1))
        assertEquals(FriendAge.STALE, FriendAge.of(30 * min))
        assertEquals(8, FriendAge.minutes(8 * min + 59_000))
        assertEquals(0, FriendAge.minutes(-5_000))
    }

    @Test
    fun paceReadsTheNextUpdateCode() {
        assertEquals(FriendPace.MOVING, FriendPace.of(NextUpdate.of(60_000)))
        assertEquals(FriendPace.MOVING, FriendPace.of(NextUpdate.of(5 * min)))
        assertEquals(FriendPace.STATIONARY, FriendPace.of(NextUpdate.of(10 * min)))
        assertEquals(FriendPace.STATIONARY, FriendPace.of(NextUpdate.of(15 * min)))
        assertEquals(FriendPace.ON_REQUEST, FriendPace.of(NextUpdate.ON_REQUEST))
        assertEquals(FriendPace.UNKNOWN, FriendPace.of(NextUpdate.SESSION_ENDED))
        assertEquals(FriendPace.UNKNOWN, FriendPace.of(NextUpdate.UNKNOWN))
    }

    @Test
    fun ringGrowsHalfAMetreASecondUpToItsCaps() {
        assertEquals(16.0, FriendRing.accuracy95(AccuracyClass.of(8.0)), 1e-6)
        assertEquals(FriendRing.WIDE_M, FriendRing.accuracy95(AccuracyClass.UNKNOWN), 1e-6)
        assertEquals(FriendRing.WIDE_M, FriendRing.accuracy95(AccuracyClass.OVER_200), 1e-6)
        assertEquals(16.0, FriendRing.radiusM(16.0, 0, here, null), 1e-6)
        // 8 minutes: 16 + 0.5 x 480
        assertEquals(256.0, FriendRing.radiusM(16.0, 8 * min, here, null), 1e-6)
        // an hour-old position off the site keeps its whole growth, well past the 300 m it used to stop at
        assertEquals(1_816.0, FriendRing.radiusM(16.0, 60 * min, here, null), 1e-6)
        val farFromSite = Bounds(44.5, 26.0, 44.51, 26.01)
        assertEquals(1_816.0, FriendRing.radiusM(16.0, 60 * min, here, farFromSite), 1e-6)
        assertEquals(FriendRing.MAX_M, FriendRing.radiusM(16.0, 4 * 60 * min, here, null), 1e-6)
        // a small site: the ring stops where the whole site is inside it
        val site = Bounds(here.lat - 0.0005, here.lon - 0.0005, here.lat + 0.0005, here.lon + 0.0005)
        val edge = FriendRing.radiusM(16.0, 40 * min, here, site)
        assertTrue(edge in 60.0..80.0)
        // never smaller than the accuracy itself
        assertEquals(200.0, FriendRing.radiusM(200.0, 0, here, site), 1e-6)
    }

    @Test
    fun arrowOnlyBeyondTwiceBothRadii() {
        assertEquals(FriendGuide.Near, Guide.of(here, 10.0, north(55.0), 20.0, null))
        val far = Guide.of(here, 10.0, north(118.0), 20.0, null)
        assertEquals(FriendGuide.Arrow(120, 0.0, 0), far.let { (it as FriendGuide.Arrow).copy(bearingDeg = it.bearingDeg.roundToLong().toDouble()) })
        val east = Guide.of(here, 3.0, GeoPoint(here.lat, here.lon + 0.003), 3.0, null) as FriendGuide.Arrow
        assertEquals(2, east.sector)
        assertEquals(90.0, east.bearingDeg, 0.5)
    }

    @Test
    fun distancesAreRounded() {
        assertEquals(120, Guide.round(118))
        assertEquals(1000, Guide.round(996))
        assertEquals(1250, Guide.round(1238))
    }

    @Test
    fun byAStageTheArrowBecomesAPlace() {
        val square = { lat: Double, lon: Double, d: Double -> listOf(GeoPoint(lat - d, lon - d), GeoPoint(lat - d, lon + d), GeoPoint(lat + d, lon + d), GeoPoint(lat + d, lon - d)) }
        val friendAtStage = north(400.0)
        val friendElsewhere = GeoPoint(here.lat, here.lon + 0.006)
        val venue = Venue(
            "test", Bounds(43.94, 28.62, 43.96, 28.65),
            listOf(
                Zone("main", "Main Stage", "#000000", square(here.lat, here.lon, 0.0004), "stage"),
                Zone("second", "Scena 2", "#000000", square(friendAtStage.lat, friendAtStage.lon, 0.0004), "stage"),
            ),
            pois = listOf(Poi("info", friendElsewhere.lat, friendElsewhere.lon + 0.0002, number = 14)),
        )
        assertEquals(FriendGuide.Toward(400, "Scena 2", null), Guide.of(here, 5.0, friendAtStage, 5.0, venue))
        assertEquals(14, (Guide.of(here, 5.0, friendElsewhere, 5.0, venue) as FriendGuide.Toward).landmark)
        // away from a stage the arrow stays
        assertTrue(Guide.of(north(-300.0), 5.0, friendElsewhere, 5.0, venue) is FriendGuide.Arrow)
    }

    @Test
    fun arrowTurnsWithTheHeading() {
        assertEquals(45f, Guide.arrowDeg(45.0, 0f), 1e-4f)
        assertEquals(315f, Guide.arrowDeg(45.0, 90f), 1e-4f)
        assertEquals(10f, Guide.arrowDeg(350.0, 340f), 1e-4f)
    }

    @Test
    fun askingWaitsAMinutePerFriend() {
        assertEquals(0L, AskLimit.waitMs(null, 1_000_000))
        assertEquals(20_000L, AskLimit.waitMs(1_000_000, 1_040_000))
        assertEquals(0L, AskLimit.waitMs(1_000_000, 1_060_000))
    }

    @Test
    fun nothingIsPickedAndTheRestrictiveOptionIsTheSuggestion() {
        val choice = ShareChoice()
        assertNull(choice.length)
        assertNull(choice.precision)
        assertFalse(choice.ready)
        assertFalse(choice.copy(length = ShareLength.HOUR_1).ready)
        assertTrue(ShareChoice(ShareLength.MIN_15, SharePrecision.EXACT).ready)
        assertEquals(ShareLength.HOUR_1, ShareChoice.SUGGESTED_LENGTH)
        assertEquals(SharePrecision.APPROXIMATE, ShareChoice.SUGGESTED_PRECISION)
        assertEquals(listOf(SharePrecision.EXACT, SharePrecision.APPROXIMATE, SharePrecision.ZONE), ShareChoice.PRECISIONS)
    }

    @Test
    fun lengthsBecomeEnds() {
        val now = 1_760_000_000_000L
        assertEquals(now + 15 * min, ShareTime.endAt(ShareLength.MIN_15, now, 0))
        assertEquals(now + 60 * min, ShareTime.endAt(ShareLength.HOUR_1, now, 0))
        assertNull(ShareTime.endAt(ShareLength.UNTIL_STOPPED, now, 0))
        // the festival day starts at 10:00 UTC: at 09:00 the day still running ends at 10:00, at 11:00 the next day
        val dayStart = 1_759_917_600_000L
        val nine = dayStart + ShareTime.DAY_MS - 60 * min
        assertEquals(dayStart + ShareTime.DAY_MS, ShareTime.endAt(ShareLength.FESTIVAL_DAY, nine, dayStart))
        assertEquals(dayStart + 2 * ShareTime.DAY_MS, ShareTime.endAt(ShareLength.FESTIVAL_DAY, nine + 2 * 60 * min, dayStart))
    }

    @Test
    fun sessionsAreRenewedUntilThePlanEnds() {
        val now = 10_000_000L
        assertEquals(120, ShareTime.sessionMinutes(null, now))
        assertEquals(120, ShareTime.sessionMinutes(now + 5 * 60 * min, now))
        assertEquals(15, ShareTime.sessionMinutes(now + 15 * min, now))
        assertEquals(1, ShareTime.sessionMinutes(now + 1_000, now))
        val long = SharePlan("fab", endAtMs = now + 5 * 60 * min, startedAtMs = now)
        assertFalse(ShareTime.needsRenew(long, now + 30 * min, now))
        assertTrue(ShareTime.needsRenew(long, now + 9 * min, now))
        val short = SharePlan("fab", endAtMs = now + 8 * min, startedAtMs = now)
        assertFalse(ShareTime.needsRenew(short, now + 8 * min, now))
        assertTrue(ShareTime.needsRenew(SharePlan("fab", null, now), now + 5 * min, now))
    }

    @Test
    fun remindersOnlyForSharesWithoutAnEnd() {
        val start = 1_000_000L
        val hours4 = ShareTime.REMINDER_MS
        assertFalse(ShareTime.reminderDue(SharePlan("f1", null, start), start + hours4 - 1))
        assertTrue(ShareTime.reminderDue(SharePlan("f1", null, start), start + hours4))
        assertFalse(ShareTime.reminderDue(SharePlan("f1", null, start, remindedAtMs = start + hours4), start + hours4 + 60 * min))
        assertFalse(ShareTime.reminderDue(SharePlan("f1", start + 2 * hours4, start), start + hours4))
    }

    @Test
    fun theEndShownIsThePlansOrElseTheSessions() {
        assertEquals(5_000L, ShareTime.endsAt(null, 5_000))
        assertNull(ShareTime.endsAt(SharePlan("f1", null, 0), 5_000))
        assertEquals(9_000L, ShareTime.endsAt(SharePlan("f1", 9_000, 0), 5_000))
    }

    @Test
    fun sharingCountsEachPersonOnceAndEndsWithTheLastShare() {
        val now = 1_000_000L
        val active = listOf(
            OutgoingShare("f1", SharePrecision.EXACT, now + 30 * min, 1),
            OutgoingShare("g1", SharePrecision.APPROXIMATE, now + 60 * min, 2),
            OutgoingShare("f9", SharePrecision.ZONE, now - 1, 3),
        )
        val recipients = mapOf("f1" to listOf(1L), "g1" to listOf(1L, 2L, 3L))
        val plans = listOf(SharePlan("g1", now + 5 * 60 * min, now))
        val sharing = SharingNow.of(active, plans, { recipients[it].orEmpty() }, now)!!
        assertEquals(listOf("f1", "g1"), sharing.shares.map { it.conversation })
        assertEquals(3, sharing.people)
        assertEquals(now + 5 * 60 * min, sharing.endsAtMs)
        assertNull(SharingNow.of(active, listOf(SharePlan("f1", null, now)), { recipients[it].orEmpty() }, now)!!.endsAtMs)
        assertNull(SharingNow.of(active.takeLast(1), plans, { emptyList() }, now))
    }

    @Test
    fun plansLastWhileTheirShareRuns() {
        val now = 1_000_000L
        val active = listOf(OutgoingShare("f1", SharePrecision.EXACT, now + 30 * min, 1))
        val running = SharePlan("f1", null, now - 10 * min)
        val justStarted = SharePlan("f2", now + 15 * min, now - 5_000)
        val stopped = SharePlan("f3", null, now - 10 * min)
        val over = SharePlan("f1", now - 1, now - 20 * min)
        assertEquals(listOf(running, justStarted), SharingNow.keptPlans(listOf(running, justStarted, stopped), active, now))
        assertEquals(emptyList<SharePlan>(), SharingNow.keptPlans(listOf(over), active, now))
    }
}
