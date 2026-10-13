package ro.safetyplease.core.chat

import ro.safetyplease.core.protocol.PositionPoint
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.distanceMeters
import ro.safetyplease.core.venue.radians
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The grid box, masking, the minute of a fix, and when a position goes. */
class PositionPolicyTest {
    private val box = PositionBox(44.0, 26.0)
    private val frame = PositionFrame(box, DAY_START)

    /** [north] and [east] metres from the box's corner. */
    private fun at(north: Double, east: Double): GeoPoint = box.toLatLon(east.toInt(), north.toInt())

    @Test
    fun theBoxHoldsFourKilometresToTheMetre() {
        val p = box.toLatLon(1234, 567)
        assertEquals(1234 to 567, box.toGrid(p.lat, p.lon))
        assertEquals(0 to 0, box.toGrid(44.0, 26.0))
        assertNull(box.toGrid(43.9999, 26.001), "south of the corner")
        assertNull(box.toGrid(44.001, 26.0 + 4100 / (111_320 * cos(radians(44.0)))), "past 4,095 m east")
        val corner = box.toLatLon(4095, 4095)
        assertEquals(4095 to 4095, box.toGrid(corner.lat, corner.lon))

        // turned 30 degrees: the axes move, the round trip doesn't
        val turned = PositionBox(44.0, 26.0, 30.0, 1)
        val q = turned.toLatLon(2000, 100)
        assertEquals(2000 to 100, turned.toGrid(q.lat, q.lon))
        assertEquals(2002.0, distanceMeters(GeoPoint(44.0, 26.0), q).toDouble(), 3.0)
        assertEquals(PositionBox(10.0, 20.0), PositionBox.of(Bounds(10.0, 20.0, 10.1, 20.1)))
    }

    @Test
    fun exactIsTheMetreAndApproximateIsTheCentreOfA128mCell() {
        val p = box.toLatLon(1234, 567)
        assertEquals(PositionPoint.Grid(1234, 567), frame.point(p.lat, p.lon, approximate = false))
        // 1234 = 9 x 128 + 82, 567 = 4 x 128 + 55: the cells' centres are 9 x 128 + 64 and 4 x 128 + 64
        assertEquals(PositionPoint.Grid(1216, 576), frame.point(p.lat, p.lon, approximate = true))
        val nearby = box.toLatLon(1160, 520)
        assertEquals(PositionPoint.Grid(1216, 576), frame.point(nearby.lat, nearby.lon, approximate = true), "the same cell, the same point")

        // outside the box: degrees, and a cell of about 128 m around the true point
        val far = GeoPoint(44.4268, 26.1025)
        assertEquals(PositionPoint.Absolute(44.4268, 26.1025), frame.point(far.lat, far.lon, approximate = false))
        val masked = frame.latLon(frame.point(far.lat, far.lon, approximate = true))!!
        assertTrue(distanceMeters(far, masked) <= PositionFrame.APPROXIMATE_RADIUS_M)
        assertTrue(distanceMeters(far, masked) > 0, "not the point itself")

        assertNull(PositionFrame.NONE.latLon(PositionPoint.Grid(1, 1)), "a grid point means nothing without the box")
    }

    @Test
    fun aFixMinuteComesBackAsTheTimeNearestNow() {
        val fix = DAY_START + 600 * 60_000L + 30_000
        val minute = frame.fixMinute(fix)
        assertEquals(600, minute)
        assertEquals(DAY_START + 600 * 60_000L, frame.fixTime(minute, fix + 5 * 60_000L))
        // 46 days on, the counter has wrapped once
        val later = DAY_START + 46L * 24 * 60 * 60_000L
        assertEquals(later, frame.fixTime(frame.fixMinute(later), later + 60_000))
        assertEquals(0xFFFF, frame.fixMinute(DAY_START - 1), "before the day start")
        assertEquals(DAY_START - 60_000L, frame.fixTime(0xFFFF, DAY_START))
    }

    @Test
    fun theFrameComesFromThePackOrTheVenue() {
        val bounds = Bounds(44.0, 26.0, 44.01, 26.01)
        val fromVenue = PositionFrame.of(null, bounds)
        assertEquals(PositionBox(44.0, 26.0), fromVenue.box)
        assertEquals(0L, fromVenue.dayStartMs)
        assertNull(PositionFrame.of(null, null).box)
    }

    @Test
    fun theAdaptiveIntervalSharesThePositionsAirAmongTheLiveKeys() {
        assertEquals(60_000L, PositionPolicy.intervalMs(1))
        assertEquals(60_000L, PositionPolicy.intervalMs(42))
        assertEquals(143_000L, PositionPolicy.intervalMs(100))
        assertEquals(286_000L, PositionPolicy.intervalMs(200))
        assertEquals(429_000L, PositionPolicy.intervalMs(300))
        assertEquals(600_000L, PositionPolicy.intervalMs(420))
        assertEquals(60_000L, PositionPolicy.intervalMs(300, perPacket = 14), "records batched by 14")
    }

    @Test
    fun aMoveOf50mGoesAfterAMinuteAndASmallerOneNever() {
        val policy = PositionPolicy()
        val start = at(1000.0, 1000.0)
        assertTrue(policy.due(0, start, 5, 1), "the first fix goes at once")
        policy.sent(0, start, 5)

        val moved = at(1060.0, 1000.0)
        assertFalse(policy.due(59_000, moved, 5, 1), "not before a minute")
        assertTrue(policy.due(60_000, moved, 5, 1))
        assertFalse(policy.due(60_000, at(1049.0, 1000.0), 5, 1), "49 m is no move")
        assertFalse(policy.due(60_000, moved, 80, 1), "nor 60 m when the fix may be 80 m off")
        assertFalse(policy.due(428_000, moved, 5, 300), "with 300 live keys a move waits 429 s")
        assertTrue(policy.due(429_000, moved, 5, 300))
    }

    @Test
    fun standingStillSendsHeartbeatsAt2_5_10_15MinutesAndAMoveStartsAgain() {
        val policy = PositionPolicy()
        val here = at(500.0, 500.0)
        policy.sent(0, here, 5)
        assertEquals(120_000L, policy.nextUpdateMs(1))

        var t = 0L
        for (gap in listOf(2, 5, 10, 15, 15).map { it * 60_000L }) {
            assertFalse(policy.due(t + gap - 1_000, here, 5, 1))
            assertTrue(policy.due(t + gap, here, 5, 1))
            t += gap
            policy.sent(t, here, 5)
        }
        assertEquals(15 * 60_000L, policy.nextUpdateMs(1))

        val moved = at(560.0, 500.0)
        assertTrue(policy.due(t + 60_000, moved, 5, 1))
        policy.sent(t + 60_000, moved, 5)
        assertEquals(120_000L, policy.nextUpdateMs(1), "a move starts the series again")
        assertTrue(policy.due(t + 180_000, moved, 5, 1))
        assertEquals(429_000L, policy.nextUpdateMs(300), "never closer than the adaptive interval")

        policy.reset()
        assertTrue(policy.due(t + 61_000, moved, 5, 1))
    }

    @Test
    fun whereAreYouIsOncePerFriendAMinute() {
        val limit = PerFriendLimit(60_000)
        assertTrue(limit.tryTake(1, 0))
        assertFalse(limit.tryTake(1, 59_999))
        assertTrue(limit.tryTake(2, 30_000), "another friend")
        assertTrue(limit.tryTake(1, 60_000))
    }

    private companion object {
        const val DAY_START = 1_759_968_000_000L
    }
}
