package ro.safetyplease.core.protocol

import ro.safetyplease.core.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The report's extension block and inner kinds 17 to 19: what they write, and what a reader does with the unexpected. */
class ContractCodecTest {
    private val body = IncidentBody(IncidentCategory.MEDICAL, Severity.URGENT, 1_760_000_000L, "Bar", null, null, "", null)

    private fun extras(block: String) = IncidentBody.decode(body.encode().also { it[6] = (it[6].toInt() or 0x80).toByte() } + block.hexToBytes())!!.extras

    @Test
    fun aBodyWithoutExtrasKeepsItsBytes() {
        val plain = body.encode()
        assertEquals(0, plain[6].toInt() and 0x80, "no flag, no block")
        assertContentEquals(plain, IncidentBody.decode(plain)!!.encode())
    }

    @Test
    fun everyEntryComesBack() {
        val all = ReportExtras(LastSeen(44.4271, 26.1031, 25, 2), 90, anonymous = true, witness = true, density = 5, cannotMove = true, manDown = true, unconscious = true)
        val decoded = IncidentBody.decode(IncidentBody(body.category, body.severity, body.timestamp, "", null, null, "", null, extras = all).encode())!!
        assertEquals(all, decoded.extras)
    }

    @Test
    fun theBlockSkipsWhatItDoesNotKnowAndKeepsReading() {
        // an unknown type 0x63 with two bytes, then "unconscious"
        assertEquals(ReportExtras(unconscious = true), extras("6302abcd0800"))
        // a newer version appended a byte to the density: the first is still read
        assertEquals(ReportExtras(density = 3), extras("05020309"))
        // too short for what it holds, or out of range: left out, the rest still read
        assertEquals(ReportExtras(witness = true), extras("0100" + "0401aa" + "050106" + "02020600"))
        // an entry running past the body ends the block
        assertEquals(ReportExtras(anonymous = true), extras("0300" + "01ff"))
        assertEquals(ReportExtras.NONE, extras("06"))
    }

    @Test
    fun theReporterCannotWriteAnImpossibleValue() {
        assertFailsWith<IllegalArgumentException> { ReportExtras(happenedMinAgo = ReportExtras.MAX_HAPPENED_MIN + 1) }
        assertFailsWith<IllegalArgumentException> { ReportExtras(density = 6) }
        assertFailsWith<IllegalArgumentException> { LastSeen(44.0, 26.0, 1, 8) }
    }

    @Test
    fun theNewInnerKindsRoundTrip() {
        val fix = LastFix(-22.951916, -43.210487, 1_760_000_600L, AccuracyClass.UNKNOWN)
        val inners = listOf(
            Inner.LostSession(7, null, LostAction.FOUND, 255, LostAction.MAX_MINUTES, BatteryLevel.UNKNOWN, 0),
            Inner.LastGasp(fix, 0),
            Inner.CheckinMissed(9, 11, 1_760_007_200L, null, ""),
            Inner.CheckinMissed(9, null, 1_760_007_200L, fix, "Cazare"),
        )
        for (inner in inners) assertContentEquals(InnerCodec.encode(inner), InnerCodec.encode(InnerCodec.decode(InnerCodec.encode(inner))!!))
    }

    @Test
    fun malformedNewInnerKindsAreRejected() {
        val lost = InnerCodec.encode(Inner.LostSession(7, null, LostAction.START, 1, 15, 50, 3))
        fun lostWith(at: Int, value: Int) = lost.copyOf().also { it[at] = value.toByte() }
        // after type, msgId and the flags byte: action, seq, minutes, battery, landmark
        assertNull(InnerCodec.decode(lostWith(10, 3)), "action")
        assertNull(InnerCodec.decode(lostWith(12, 0)), "minutes")
        assertNull(InnerCodec.decode(lostWith(13, 101)), "battery")
        assertNull(InnerCodec.decode(lost + 0), "trailing byte")
        val gasp = InnerCodec.encode(Inner.LastGasp(LastFix(44.0, 26.0, 1, 0), 10))
        assertNull(InnerCodec.decode(gasp.copyOf().also { it[gasp.size - 1] = 8 }), "accuracy class")
        assertNull(InnerCodec.decode(gasp.copyOf(gasp.size - 1)), "truncated")
        val checkin = InnerCodec.encode(Inner.CheckinMissed(9, null, 1, null, "Gară"))
        assertNull(InnerCodec.decode(checkin.copyOf().also { it[9] = 0x04 }), "an unknown flag")
        assertFailsWith<IllegalArgumentException> { InnerCodec.encode(Inner.CheckinMissed(9, null, 1, null, "x".repeat(Limits.CHECKIN_LABEL_BYTES + 1))) }
    }
}
