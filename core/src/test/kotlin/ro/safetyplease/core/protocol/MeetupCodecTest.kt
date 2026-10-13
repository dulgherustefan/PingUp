package ro.safetyplease.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The inner kinds of "Fă-te văzut", meeting points and "Vin la tine" (PROTOCOL.md, section 6.6.1). */
class MeetupCodecTest {
    private fun roundTrip(inner: Inner): Inner = InnerCodec.decode(InnerCodec.encode(inner))!!

    @Test
    fun aPingRequestIsThreeBytes() {
        val ask = Inner.PingRequest(PingAction.ASK, 10)
        assertEquals(3, InnerCodec.encode(ask).size)
        val back = roundTrip(Inner.PingRequest(PingAction.LIGHTING, PingAction.MAX_SECONDS)) as Inner.PingRequest
        assertEquals(listOf(PingAction.LIGHTING, PingAction.MAX_SECONDS), listOf(back.action, back.seconds))

        assertNull("unknown action", InnerCodec.decode(byteArrayOf(13, 3, 10)))
        assertNull("no seconds", InnerCodec.decode(byteArrayOf(13, 1, 0)))
        assertNull("too long", InnerCodec.decode(byteArrayOf(13, 1, 61)))
        assertNull("a byte too many", InnerCodec.decode(byteArrayOf(13, 1, 10, 0)))
    }

    @Test
    fun aMeetingPointCarriesALandmarkOrAPointATimeAndAnExpiry() {
        val landmark = Inner.MeetingPoint(5L, 9L, 200, 255, null, null, false, 1439, 720)
        assertEquals(1 + 8 + 1 + 8 + 1 + 1 + 2 + 2, InnerCodec.encode(landmark).size)
        val back = roundTrip(landmark) as Inner.MeetingPoint
        assertEquals(listOf(5L, 9L, 200, 255, null, null, false, 1439, 720), fields(back))

        val point = Inner.MeetingPoint(6L, null, 1, 0, 44.123456, 26.654321, true, null, 1)
        assertEquals(1 + 8 + 1 + 1 + 1 + 8 + 2, InnerCodec.encode(point).size)
        assertEquals(listOf(6L, null, 1, 0, 44.123456, 26.654321, true, null, 1), fields(roundTrip(point) as Inner.MeetingPoint))
    }

    @Test
    fun aMeetingPointThatCantBeRightIsRefused() {
        val bytes = InnerCodec.encode(Inner.MeetingPoint(5L, null, 1, 14, null, null, false, 600, 60))
        fun with(index: Int, value: Int) = bytes.copyOf().also { it[index] = value.toByte() }
        val flags = 9
        assertNull("a venue point with a landmark", InnerCodec.decode(with(flags, 0x02 or 0x04)))
        assertNull("an unknown flag", InnerCodec.decode(with(flags, 0x02 or 0x08)))
        assertNull("minute 1440", InnerCodec.decode(with(12, 0x05).also { it[13] = 0xA0.toByte() }))
        assertNull("no expiry", InnerCodec.decode(with(15, 0)))
        assertNull("cut short", InnerCodec.decode(bytes.copyOf(bytes.size - 1)))
        assertNull("a byte too many", InnerCodec.decode(bytes + 0))
    }

    @Test
    fun aMeetingReplyNamesTheProposal() {
        val reply = Inner.MeetingReply(1L, 2L, MeetingAction.WITHDRAW, 3L)
        assertEquals(27, InnerCodec.encode(reply).size)
        val back = roundTrip(reply) as Inner.MeetingReply
        assertEquals(listOf(1L, 2L, MeetingAction.WITHDRAW, 3L), listOf(back.msgId, back.groupId, back.action, back.proposalId))
        val bytes = InnerCodec.encode(Inner.MeetingReply(1L, null, MeetingAction.ACCEPT, 3L))
        assertNull("unknown action", InnerCodec.decode(bytes.copyOf().also { it[10] = 3 }))
    }

    @Test
    fun comingIsAShareSessionWithTwoNewActions() {
        for (action in listOf(ShareAction.COME_ASK, ShareAction.COME_AGREE)) {
            val back = roundTrip(Inner.ShareSession(action, 15, SharePrecision.APPROXIMATE, 4)) as Inner.ShareSession
            assertEquals(listOf(action, 15, SharePrecision.APPROXIMATE, 4), listOf(back.action, back.durationMin, back.precision, back.seq))
        }
    }

    @Test
    fun theBuildBeforeThisOneHasNoCaseForThem() {
        // the 1.1 position build read kinds up to 12 and share actions up to 4 (PositionCodecTest): anything past that it
        // fails to decode and drops silently, like any unknown kind (ChatExtrasTest)
        val ping = InnerCodec.encode(Inner.PingRequest(PingAction.ASK, 10))
        val meeting = InnerCodec.encode(Inner.MeetingPoint(1L, null, 1, 3, null, null, false, null, 30))
        val reply = InnerCodec.encode(Inner.MeetingReply(1L, null, MeetingAction.ACCEPT, 2L))
        val come = InnerCodec.encode(Inner.ShareSession(ShareAction.COME_ASK, 15, SharePrecision.EXACT, 1))
        assertEquals(listOf(13, 14, 15), listOf(ping[0], meeting[0], reply[0]).map { it.toInt() })
        assertTrue(come[1] > ShareAction.ACCEPT)
        assertNull("a kind after these is still dropped", InnerCodec.decode(byteArrayOf(16, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    private fun fields(m: Inner.MeetingPoint) = listOf(m.msgId, m.groupId, m.seq, m.landmark, m.lat, m.lon, m.venuePoint, m.minuteOfDay, m.expiryMin)
}
