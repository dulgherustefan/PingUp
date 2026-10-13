package ro.safetyplease.core.protocol

import ro.safetyplease.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The inner kinds of "Fă-te văzut", meeting points and "Vin la tine" (PROTOCOL.md, section 6.6.1). */
class MeetupCodecTest {
    private fun roundTrip(inner: Inner): Inner = InnerCodec.decode(InnerCodec.encode(inner))!!

    @Test
    fun aPingRequestIsThreeBytes() {
        val ask = Inner.PingRequest(PingAction.ASK, 10)
        assertEquals(3, InnerCodec.encode(ask).size)
        val back = roundTrip(Inner.PingRequest(PingAction.LIGHTING, PingAction.MAX_SECONDS)) as Inner.PingRequest
        assertEquals(listOf(PingAction.LIGHTING, PingAction.MAX_SECONDS), listOf(back.action, back.seconds))

        assertNull(InnerCodec.decode(byteArrayOf(14, 3, 10)), "unknown action")
        assertNull(InnerCodec.decode(byteArrayOf(14, 1, 0)), "no seconds")
        assertNull(InnerCodec.decode(byteArrayOf(14, 1, 61)), "too long")
        assertNull(InnerCodec.decode(byteArrayOf(14, 1, 10, 0)), "a byte too many")
    }

    @Test
    fun aMeetingPointCarriesALandmarkOrAPointATimeAndAnExpiry() {
        val landmark = Inner.MeetingPoint(5L, 9L, 200, 255, null, null, false, MeetingLimits.MAX_MEET_AT, 720)
        assertEquals(1 + 8 + 1 + 8 + 1 + 1 + 4 + 2, InnerCodec.encode(landmark).size)
        val back = roundTrip(landmark) as Inner.MeetingPoint
        assertEquals(listOf(5L, 9L, 200, 255, null, null, false, MeetingLimits.MAX_MEET_AT, 720), fields(back))

        val point = Inner.MeetingPoint(6L, null, 1, 0, 44.123456, 26.654321, true, 1_760_001_800L, 1)
        assertEquals(1 + 8 + 1 + 1 + 1 + 8 + 4 + 2, InnerCodec.encode(point).size)
        assertEquals(listOf(6L, null, 1, 0, 44.123456, 26.654321, true, 1_760_001_800L, 1), fields(roundTrip(point) as Inner.MeetingPoint))
    }

    @Test
    fun theLargestMeetingPointIs34BytesAndTheTimeIsAMomentInSeconds() {
        val largest = Inner.MeetingPoint(1L, 2L, 255, 0, 44.123456, 26.654321, true, 1_760_001_800L, MeetingLimits.MAX_EXPIRY_MIN)
        val bytes = InnerCodec.encode(largest)
        assertEquals(34, bytes.size)
        // the four bytes before the expiry: Unix seconds, big-endian (PROTOCOL.md, section 6.6.1)
        assertEquals("68e77f08", bytes.copyOfRange(bytes.size - 6, bytes.size - 2).toHex())

        val first = roundTrip(Inner.MeetingPoint(1L, null, 1, 3, null, null, false, 1L, 30)) as Inner.MeetingPoint
        assertEquals(1L, first.meetAt, "the second after the epoch is a time")
    }

    @Test
    fun noTimeIsFourZeroBytesAndComesBackAsNull() {
        val none = Inner.MeetingPoint(1L, null, 1, 3, null, null, false, null, 30)
        val bytes = InnerCodec.encode(none)
        assertEquals(1 + 8 + 1 + 1 + 1 + 4 + 2, bytes.size)
        assertEquals("00000000", bytes.copyOfRange(bytes.size - 6, bytes.size - 2).toHex())
        assertNull((InnerCodec.decode(bytes) as Inner.MeetingPoint).meetAt)

        // 0 is the wire's way to say "none", so it is never a time to build one with, and a u32 is as far as a time goes
        fun meeting(meetAt: Long) = Inner.MeetingPoint(1L, null, 1, 3, null, null, false, meetAt, 30)
        assertFailsWith<IllegalArgumentException> { meeting(0L) }
        assertFailsWith<IllegalArgumentException> { meeting(-1L) }
        assertFailsWith<IllegalArgumentException> { meeting(MeetingLimits.MAX_MEET_AT + 1) }
    }

    @Test
    fun aMeetingPointThatCantBeRightIsRefused() {
        val bytes = InnerCodec.encode(Inner.MeetingPoint(5L, null, 1, 14, null, null, false, 1_760_001_800L, 60))
        fun with(index: Int, value: Int) = bytes.copyOf().also { it[index] = value.toByte() }
        val flags = 9
        val expiry = 16
        assertEquals(18, bytes.size)
        assertNull(InnerCodec.decode(with(flags, 0x04)), "a venue point with a landmark")
        assertNull(InnerCodec.decode(with(flags, 0x02)), "0x02 is no flag: the time is always there")
        assertNull(InnerCodec.decode(with(flags, 0x08)), "an unknown flag")
        assertNull(InnerCodec.decode(with(expiry + 1, 0)), "no expiry")
        assertNull(InnerCodec.decode(with(expiry, 0x02).also { it[expiry + 1] = 0xD1.toByte() }), "an expiry of 721 minutes")
        assertNull(InnerCodec.decode(bytes.copyOf(bytes.size - 1)), "cut short")
        assertNull(InnerCodec.decode(bytes.copyOf(bytes.size - 3)), "cut short in the time")
        assertNull(InnerCodec.decode(bytes + 0), "a byte too many")
    }

    @Test
    fun aMeetingReplyNamesTheProposal() {
        val reply = Inner.MeetingReply(1L, 2L, MeetingAction.WITHDRAW, 3L)
        assertEquals(27, InnerCodec.encode(reply).size)
        val back = roundTrip(reply) as Inner.MeetingReply
        assertEquals(listOf(1L, 2L, MeetingAction.WITHDRAW, 3L), listOf(back.msgId, back.groupId, back.action, back.proposalId))
        val bytes = InnerCodec.encode(Inner.MeetingReply(1L, null, MeetingAction.ACCEPT, 3L))
        assertNull(InnerCodec.decode(bytes.copyOf().also { it[10] = 3 }), "unknown action")
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
        // the 1.1 position build read kinds up to 13 and share actions up to 4 (PositionCodecTest): anything past that it
        // fails to decode and drops silently, like any unknown kind (ChatExtrasTest)
        val ping = InnerCodec.encode(Inner.PingRequest(PingAction.ASK, 10))
        val meeting = InnerCodec.encode(Inner.MeetingPoint(1L, null, 1, 3, null, null, false, null, 30))
        val reply = InnerCodec.encode(Inner.MeetingReply(1L, null, MeetingAction.ACCEPT, 2L))
        val come = InnerCodec.encode(Inner.ShareSession(ShareAction.COME_ASK, 15, SharePrecision.EXACT, 1))
        assertEquals(listOf(14, 15, 16), listOf(ping[0], meeting[0], reply[0]).map { it.toInt() })
        assertTrue(come[1] > ShareAction.ACCEPT)
        assertNull(InnerCodec.decode(byteArrayOf(17, 0, 0, 0, 0, 0, 0, 0, 0, 0)), "a kind after these is still dropped")
    }

    private fun fields(m: Inner.MeetingPoint) = listOf(m.msgId, m.groupId, m.seq, m.landmark, m.lat, m.lon, m.venuePoint, m.meetAt, m.expiryMin)
}
