package ro.safetyplease.app.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.data.Conversations
import ro.safetyplease.core.data.PositionData
import ro.safetyplease.core.position.FriendAge
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.distanceMeters
import java.io.File
import java.time.Instant
import java.time.ZoneId

class DemoFriendsTest {
    private val venue = Venue.parse(File("src/main/assets/venue.json").readText())
    private val now = 1_791_800_000_000L

    @Test
    fun threeFriendsFreshCloseOldAndStaleAndOneShareOfMine() {
        val center = DemoFriends.center(venue, null)
        assertTrue(venue.bounds.contains(center.lat, center.lon))
        val data = DemoFriends.positions(PositionData(), center, now)
        val records = data.records.sortedBy { it.sender }
        assertEquals(listOf(FriendAge.NOW, FriendAge.RECENT, FriendAge.STALE), records.map { FriendAge.of(it.ageMs(now)) })
        val distances = records.map { distanceMeters(center, GeoPoint(it.lat, it.lon)) }
        assertTrue(distances[0] < 40)
        assertEquals(150.0, distances[1].toDouble(), 3.0)
        assertTrue(records.all { it.expiresAtMs > now && DemoFriends.isDemo(it.sender) })
        assertEquals(listOf(Conversations.friend(DemoFriends.ID_BASE)), data.shares.map { it.conversation })
    }

    @Test
    fun runningItTwiceKeepsOneOfEach() {
        val center = DemoFriends.center(venue, null)
        val twice = DemoFriends.positions(DemoFriends.positions(PositionData(), center, now), center, now + 1_000)
        assertEquals(3, twice.records.size)
        assertEquals(3, twice.incoming.size)
        assertEquals(1, twice.shares.size)
    }

    @Test
    fun theFirstFriendPinsALandmarkOfTheVenueHalfAnHourAhead() {
        val proposal = DemoFriends.meeting(venue, now)!!
        assertEquals(Conversations.friend(DemoFriends.ID_BASE), proposal.conversation)
        assertEquals(DemoFriends.ID_BASE, proposal.proposer)
        assertNotNull(venue.landmark(proposal.place.landmark))
        val at = Instant.ofEpochMilli(now + 30 * 60_000L).atZone(ZoneId.systemDefault())
        assertEquals(at.hour * 60 + at.minute, proposal.minuteOfDay)
        assertTrue(proposal.expiresAtMs > now)
    }

    @Test
    fun withoutLandmarksItIsTheVenuesMeetingPointAndWithoutThatNothing() {
        val bare = venue.copy(pois = emptyList())
        assertTrue(DemoFriends.meeting(bare, now)!!.place.venuePoint)
        assertNull(DemoFriends.meeting(bare.copy(meetingPoint = null), now))
    }
}
