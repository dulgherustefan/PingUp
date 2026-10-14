package ro.safetyplease.app.demo

import ro.safetyplease.app.AppContainer
import ro.safetyplease.core.chat.MeetingPlace
import ro.safetyplease.core.chat.MeetingProposal
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.Friend
import ro.safetyplease.core.data.IncomingShare
import ro.safetyplease.core.data.OutgoingShare
import ro.safetyplease.core.data.PositionData
import ro.safetyplease.core.data.PositionRecord
import ro.safetyplease.core.position.SharePlan
import ro.safetyplease.core.protocol.AccuracyClass
import ro.safetyplease.core.protocol.NextUpdate
import ro.safetyplease.core.protocol.PositionMethod
import ro.safetyplease.core.protocol.SharePrecision
import ro.safetyplease.core.util.toHex
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import kotlin.math.cos

/**
 * The "prieteni" command: three friends sharing their position around [center] (one fresh and close, one 8 minutes
 * old 150 m away, one 40 minutes old) and this phone sharing with the first, who has also pinned a meeting point in their
 * chat, so the map, the lists and the pin show without radio.
 */
object DemoFriends {
    /** Demo friends' ids start with this, so the command can be run again and reset can find them. */
    const val ID_BASE = 0x0dec0de0f0000000L
    private const val MIN = 60_000L
    private const val SESSION_MS = 60 * MIN
    private const val FRESH_MS = 15_000L

    class Spec(val nickname: String, val northM: Double, val eastM: Double, val ageMs: Long, val accuracyM: Double, val nextUpdate: Int)

    val specs = listOf(
        Spec("Ana (demo)", 15.0, 20.0, 30_000, 6.0, NextUpdate.of(MIN)),
        Spec("Radu (demo)", -110.0, 102.0, 8 * MIN, 15.0, NextUpdate.of(10 * MIN)),
        Spec("Ioana (demo)", 180.0, -160.0, 40 * MIN, 40.0, NextUpdate.ON_REQUEST),
    )

    fun isDemo(nodeId: Long) = nodeId in ID_BASE until ID_BASE + specs.size

    /** Where the demo happens: the simulated position, else the meeting point, else the middle of the site. */
    fun center(venue: Venue, simulated: GeoPoint?): GeoPoint = simulated ?: venue.meetingPoint
        ?: GeoPoint((venue.bounds.minLat + venue.bounds.maxLat) / 2, (venue.bounds.minLon + venue.bounds.maxLon) / 2)

    fun offset(from: GeoPoint, northM: Double, eastM: Double): GeoPoint =
        GeoPoint(from.lat + northM / 111_320.0, from.lon + eastM / (111_320.0 * cos(Math.toRadians(from.lat))))

    /**
     * The friends' positions and sessions, and this phone's share with the first friend, merged over [data]; [fresh]
     * makes every position less than a minute old, as for the showcase.
     */
    fun positions(data: PositionData, center: GeoPoint, nowMs: Long, fresh: Boolean = false): PositionData {
        val records = specs.mapIndexed { i, spec ->
            val at = offset(center, spec.northM, spec.eastM)
            val ageMs = if (fresh) FRESH_MS + i * FRESH_MS else spec.ageMs
            PositionRecord(
                sender = ID_BASE + i, lat = at.lat, lon = at.lon, accuracy = AccuracyClass.of(spec.accuracyM), method = PositionMethod.GNSS,
                fixAtMs = nowMs - ageMs, receivedAtMs = nowMs - ageMs, expiresAtMs = nowMs + SESSION_MS, seq = 1,
                nextUpdate = spec.nextUpdate, hops = i + 1,
            )
        }
        val incoming = specs.indices.map { IncomingShare(ID_BASE + it, SharePrecision.EXACT, nowMs + SESSION_MS) }
        val mine = OutgoingShare(Conversations.friend(ID_BASE), SharePrecision.APPROXIMATE, nowMs + SESSION_MS, seq = 1, accepted = listOf(ID_BASE))
        return data.copy(
            records = data.records.filterNot { isDemo(it.sender) } + records,
            incoming = data.incoming.filterNot { isDemo(it.sender) } + incoming,
            shares = data.shares.filterNot { it.conversation == mine.conversation } + mine,
            readers = (data.readers + ID_BASE).distinct(),
        )
    }

    fun plan(nowMs: Long) = SharePlan(Conversations.friend(ID_BASE), nowMs + SESSION_MS, nowMs)

    /** The first friend's meeting point: the venue's highest numbered landmark, else its meeting point; 30 minutes ahead. */
    fun meeting(venue: Venue, nowMs: Long): MeetingProposal? {
        val place = venue.pois.map { it.number }.filter { it in Venue.LANDMARK_NUMBERS }.maxOrNull()?.let(MeetingPlace::landmark)
            ?: venue.meetingPoint?.let(MeetingPlace::venue) ?: return null
        val meetAtSec = (nowMs + 30 * MIN) / 1000
        return MeetingProposal(Conversations.friend(ID_BASE), ID_BASE, ID_BASE, 1, place, meetAtSec, nowMs + SESSION_MS)
    }

    fun seed(c: AppContainer, fresh: Boolean = false): String {
        val now = c.clock.wallMs()
        val settings = c.settings.value
        val simulated = settings.simLat?.let { lat -> settings.simLon?.let { GeoPoint(lat, it) } }
        val center = center(c.venue, simulated)
        val friends = specs.mapIndexed { i, spec ->
            Friend(ID_BASE + i, spec.nickname, c.crypto.boxKeyPair().publicKey.toHex(), c.crypto.signKeyPair().publicKey.toHex(), now, confirmed = true)
        }
        c.friends.update { list -> list.filterNot { isDemo(it.nodeId) } + friends }
        c.positions.update { positions(it, center, now, fresh) }
        // without a GPS fix on the emulator, "me" stands at the center so the arrow and distances have a start
        c.settings.update { s ->
            val plans = s.sharePlans.filterNot { it.conversation == Conversations.friend(ID_BASE) } + plan(now)
            if (simulated == null && c.location.fix.value?.isFresh(now) != true) s.copy(simLat = center.lat, simLon = center.lon, sharePlans = plans)
            else s.copy(sharePlans = plans)
        }
        val meeting = meeting(c.venue, now)?.also(c.chat::pinMeeting)
        val pinned = if (meeting != null) "; meeting point pinned in their chat" else ""
        return "3 friends around ${center.lat},${center.lon}; sharing with ${specs.first().nickname}$pinned"
    }

    fun remove(c: AppContainer) {
        c.friends.update { list -> list.filterNot { isDemo(it.nodeId) } }
        c.positions.update { data ->
            data.copy(
                records = data.records.filterNot { isDemo(it.sender) },
                incoming = data.incoming.filterNot { isDemo(it.sender) },
                shares = data.shares.filterNot { it.conversation == Conversations.friend(ID_BASE) },
                readers = data.readers.filterNot(::isDemo),
            )
        }
        c.settings.update { s -> s.copy(sharePlans = s.sharePlans.filterNot { it.conversation == Conversations.friend(ID_BASE) }) }
    }
}
