package ro.safetyplease.core.control

import ro.safetyplease.core.TestEvent
import ro.safetyplease.core.crypto.CertRole
import ro.safetyplease.core.mesh.Verdict
import ro.safetyplease.core.protocol.ControlChannel
import ro.safetyplease.core.protocol.Heartbeat
import ro.safetyplease.core.protocol.Packet
import ro.safetyplease.core.protocol.PacketType
import ro.safetyplease.core.protocol.PointLevel
import ro.safetyplease.core.protocol.PointStatus
import ro.safetyplease.core.util.Clock
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointBoardTest {
    private val event = TestEvent()
    private val clock = object : Clock {
        override fun wallMs() = TestEvent.START * 1000
        override fun monoMs() = 0L
    }
    private val chain = event.chain(clock)
    private val now = TestEvent.START * 1000

    private fun signed(point: Int, level: Int, issued: Long = TestEvent.START, team: String = "Apa 1") =
        event.staffCrypto.signPoint(point, level, issued, team, event.secret)

    @Test
    fun aStatusRoundTripsThroughTheControlChannelAndOnlyStaffReadsIt() {
        val status = signed(9, PointLevel.CLOSED)
        val sealed = ControlMessages.seal(event.staffCrypto, status.encode())!!
        val packet = Packet(PacketType.PRIVATE, 6, 1, 0x1234L, 0, ControlChannel.ID, true, sealed)
        val plain = ControlMessages.open(event.staffCrypto, event.secret, packet)!!
        assertEquals(Verdict.VALID, chain.checkPoint(plain))
        val read = PointStatus.decode(plain)!!
        assertEquals(listOf(9, PointLevel.CLOSED, TestEvent.START, "Apa 1"), listOf(read.point, read.level, read.issued, read.teamName))
        assertNull(Heartbeat.decode(plain), "not a heartbeat")
        assertNull(ControlMessages.open(event.staffCrypto, TestEvent().secret, packet))
        assertNull(PointStatus.decode(plain.copyOf().also { it[3] = 4 }), "a level of a newer version")
        assertNull(PointStatus.decode(plain.copyOf().also { it[2] = 0 }), "point 0")
    }

    @Test
    fun underACertificateATeamSignsOnlyInItsOwnNameAndWithinItsShift() {
        fun under(role: Int, team: String, signedAs: String = team, issued: Long = TestEvent.START): Verdict {
            val (_, secret) = event.staffCrypto.credentialFrom(event.shift(role, team, opensReports = false))!!
            return chain.checkPoint(event.staffCrypto.signPoint(4, PointLevel.FREE, issued, signedAs, secret!!).encode())
        }
        assertEquals(Verdict.VALID, under(CertRole.STAFF, "Apa 1"))
        assertEquals(Verdict.VALID, under(CertRole.CONTROL, "Control", signedAs = "Apa 1"))
        assertEquals(Verdict.REFUSED, under(CertRole.STAFF, "Apa 1", signedAs = "Apa 2"))
        assertEquals(Verdict.REFUSED, under(CertRole.STAFF, "Apa 1", issued = TestEvent.START + 3600), "from the future")
        val other = TestEvent()
        val forged = other.staffCrypto.signPoint(4, PointLevel.FREE, TestEvent.START, "Apa 1", other.secret)
        assertEquals(Verdict.INVALID, chain.checkPoint(forged.encode()), "another build's key")
    }

    @Test
    fun theLatestStatusOfAKnownPointWinsAndGoesStaleAfterHalfAnHour() {
        val known = setOf(5, 9)
        var board = PointBoard.offer(emptyMap(), signed(5, PointLevel.BUSY, TestEvent.START - 60), known, now)
        board = PointBoard.offer(board, signed(5, PointLevel.FREE, TestEvent.START - 120), known, now)
        assertEquals(PointLevel.BUSY, board.getValue(5).level, "an older one doesn't replace it")
        board = PointBoard.offer(board, signed(5, PointLevel.CLOSED, TestEvent.START), known, now)
        assertEquals(PointLevel.CLOSED, board.getValue(5).level)
        assertEquals(board, PointBoard.offer(board, signed(77, PointLevel.FREE), known, now), "a point the venue doesn't list")

        val ahead = PointBoard.offer(board, signed(9, PointLevel.FREE, TestEvent.START + 3600), known, now).getValue(9)
        assertEquals(now, ahead.atMs, "a clock ahead counts from arrival")
        val report = board.getValue(5)
        assertFalse(PointBoard.isStale(report, now + PointBoard.STALE_MS - 1))
        assertTrue(PointBoard.isStale(report, now + PointBoard.STALE_MS))
    }

    @Test
    fun aPhoneSendsAChangeOrAConfirmationButNotATapStorm() {
        val sent = mapOf(5 to SentPoint(PointLevel.BUSY, 0))
        assertTrue(PointBoard.maySend(sent, 9, PointLevel.FREE, 0))
        assertFalse(PointBoard.maySend(sent, 5, PointLevel.FREE, PointBoard.MIN_GAP_MS - 1))
        assertTrue(PointBoard.maySend(sent, 5, PointLevel.FREE, PointBoard.MIN_GAP_MS))
        assertFalse(PointBoard.maySend(sent, 5, PointLevel.BUSY, PointBoard.CONFIRM_MS - 1))
        assertTrue(PointBoard.maySend(sent, 5, PointLevel.BUSY, PointBoard.CONFIRM_MS))
    }

    @Test
    fun theTeamsOwnPointsComeFirst() {
        val venue = Venue(
            "Fest", Bounds(0.0, 0.0, 1.0, 1.0), emptyList(),
            pois = listOf(
                Poi("wc", 0.1, 0.1, "WC", 1),
                Poi("info", 0.1, 0.1, "Info", 2),
                Poi("water", 0.1, 0.1, "Apa", 3, team = "Apa 1"),
                Poi("exit", 0.1, 0.1, "Poarta"),
                Poi("transport", 0.1, 0.1, "Gara", 4),
            ),
        )
        assertEquals(listOf(1, 3, 4), PointBoard.points(venue).map { it.number })
        assertEquals(listOf(3, 1, 4), PointBoard.forTeam(venue, "Apa 1").map { it.number })
    }
}
