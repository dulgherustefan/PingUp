package ro.safetyplease.core.protocol

import ro.safetyplease.core.chat.PositionFrame
import ro.safetyplease.core.venue.PositionBox
import ro.safetyplease.core.venue.distanceMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two inner kinds of shared positions (PROTOCOL.md, section 6.6). */
class PositionCodecTest {
    private fun roundTrip(inner: Inner): Inner = InnerCodec.decode(InnerCodec.encode(inner))!!

    @Test
    fun aPositionIsTenBytesInTheGridAndFifteenOutsideIt() {
        val grid = Inner.Position(PositionPoint.Grid(4095, 0), AccuracyClass.UNKNOWN, PositionMethod.UNKNOWN, 0xFFFF, 0, 0, 254)
        val bytes = InnerCodec.encode(grid)
        assertEquals(10, bytes.size)
        val back = InnerCodec.decode(bytes) as Inner.Position
        assertEquals(PositionPoint.Grid(4095, 0), back.point)
        assertEquals(listOf(7, 3, 0xFFFF, 0, 0, 254), listOf(back.accuracy, back.method, back.fixMinute, back.seq, back.nextUpdate, back.heading))

        val far = Inner.Position(PositionPoint.Absolute(44.4268, 26.1025), 0, PositionMethod.GNSS, 1, 2, 3, Heading.UNKNOWN)
        assertEquals(15, InnerCodec.encode(far).size)
        assertEquals(PositionPoint.Absolute(44.4268, 26.1025), (roundTrip(far) as Inner.Position).point)
    }

    @Test
    fun whatThisBuildSendsIsInDegreesEvenInsideTheBoxSoAnotherBoxReadsItRight() {
        val box = PositionBox(44.0, 26.0)
        val inside = box.toLatLon(100, 100)
        val point = PositionFrame(box, 0).point(inside.lat, inside.lon, approximate = false)
        val bytes = InnerCodec.encode(Inner.Position(point, 1, PositionMethod.GNSS, 0, 0, 0, Heading.UNKNOWN))
        assertEquals(15, bytes.size)
        assertEquals(0x01, bytes[1].toInt() and 0xC1, "absolute, the reserved bits clear")
        // the receiver holds the bundled venue's box, about 1.4 km off the event pack's: a grid point would land there
        val other = PositionFrame(PositionBox(44.01, 26.01), 0)
        val back = InnerCodec.decode(bytes) as Inner.Position
        assertTrue(distanceMeters(other.latLon(back.point)!!, inside) <= 1)
    }

    @Test
    fun aShareSessionIsSixBytes() {
        for (action in ShareAction.START..ShareAction.ACCEPT) {
            val session = Inner.ShareSession(action, 120, SharePrecision.ZONE, 200)
            assertEquals(6, InnerCodec.encode(session).size)
            val back = roundTrip(session) as Inner.ShareSession
            assertEquals(listOf(action, 120, SharePrecision.ZONE, 200), listOf(back.action, back.durationMin, back.precision, back.seq))
        }
    }

    @Test
    fun reservedBitsAreIgnoredAndWhatCantBeReadIsRefused() {
        val bytes = InnerCodec.encode(Inner.Position(PositionPoint.Grid(1, 2), 1, 0, 0, 0, 0, 0))
        bytes[1] = (bytes[1].toInt() or 0xC0).toByte()
        assertEquals(PositionPoint.Grid(1, 2), (InnerCodec.decode(bytes) as Inner.Position).point)

        assertNull(InnerCodec.decode(bytes + 0), "a byte too many")
        assertNull(InnerCodec.decode(bytes.copyOf(9)), "a grid position cut short")
        assertNull(InnerCodec.decode(byteArrayOf(13, 7, 0, 10, 0, 1)), "unknown action")
        assertNull(InnerCodec.decode(byteArrayOf(13, 1, 0, 10, 3, 1)), "unknown precision")
        assertNull(InnerCodec.decode(byteArrayOf(13, 1, 0, 10, 0)), "a session cut short")
    }

    @Test
    fun olderBuildsHaveNoCaseForTheNewKinds() {
        // an older build reads the kind byte, finds no case for it and drops the message without an answer (ChatExtrasTest)
        val position = InnerCodec.encode(Inner.Position(PositionPoint.Grid(1, 2), 1, 0, 0, 0, 0, 0))
        val session = InnerCodec.encode(Inner.ShareSession(ShareAction.START, 30, SharePrecision.EXACT, 1))
        assertEquals(12, position[0].toInt())
        assertEquals(13, session[0].toInt())
        assertTrue(position.size < 1 + 8 + 2 && session.size < 9, "both are shorter than the msgId every older kind starts with")
        assertNull(InnerCodec.decode(byteArrayOf(17, 0, 0, 0, 0, 0, 0, 0, 0)), "a kind after these is still dropped")
    }

    @Test
    fun accuracyHeadingAndNextUpdateCodes() {
        assertEquals(listOf(0, 0, 1, 2, 3, 4, 5, 6, 7, 7), listOf(1.0, 5.0, 5.1, 20.0, 50.0, 99.0, 200.0, 201.0, 0.0, null).map { AccuracyClass.of(it) })
        assertEquals(listOf(5, 10, 20, 50, 100, 200, null, null), (0..7).map(AccuracyClass::meters))

        assertEquals(0, Heading.of(0.0))
        assertEquals(0, Heading.of(360.0))
        assertEquals(64, Heading.of(90.0))
        assertEquals(254, Heading.of(358.6))
        assertEquals(0, Heading.of(359.5))
        assertEquals(Heading.UNKNOWN, Heading.of(null))
        assertEquals(90, Heading.degrees(64))
        assertEquals(359, Heading.degrees(254))
        assertNull(Heading.degrees(Heading.UNKNOWN))

        assertEquals(listOf(0, 1, 2, 3, 4, NextUpdate.UNKNOWN), listOf(60_000L, 61_000L, 300_000L, 600_000L, 900_000L, 900_001L).map(NextUpdate::of))
        assertEquals(120_000L, NextUpdate.ms(1))
        assertNull(NextUpdate.ms(NextUpdate.ON_REQUEST))
        assertNull(NextUpdate.ms(42), "a code from a newer version")
    }
}
