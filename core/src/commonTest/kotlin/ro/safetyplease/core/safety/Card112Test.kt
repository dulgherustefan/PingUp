package ro.safetyplease.core.safety

import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Poi
import ro.safetyplease.core.venue.SiteGrid
import ro.safetyplease.core.venue.StraightLine
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.WalkDistance
import ro.safetyplease.core.venue.Zone
import ro.safetyplease.core.venue.distanceMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The card a person reads aloud to the 112 operator. */
class Card112Test {
    private val here = GeoPoint(44.12345, 28.56789)

    private fun square(center: GeoPoint, half: Double) = listOf(
        GeoPoint(center.lat - half, center.lon - half), GeoPoint(center.lat - half, center.lon + half),
        GeoPoint(center.lat + half, center.lon + half), GeoPoint(center.lat + half, center.lon - half),
    )

    private val venue = Venue(
        "Beach, Please!", Bounds(44.12, 28.56, 44.13, 28.58),
        zones = listOf(
            Zone("plaja", "Plaja mare", "#00AA00", square(here, 0.001)),
            Zone("med", "Cortul medical", "#FF0000", square(GeoPoint(44.1250, 28.5700), 0.0002), type = "medical"),
        ),
        pois = listOf(
            Poi("exit", 44.12346, 28.56795, "Poarta 1", number = 3),
            Poi(Venue.PHONE, 44.1240, 28.5680, "Telefon la intrare"),
            Poi(Venue.PHONE, 44.1270, 28.5720, "Telefon departe"),
        ),
    )
    private val grid = SiteGrid.of(venue.bounds)

    @Test
    fun theCoordinatesHaveFiveDecimalsWhateverThePhonesLanguage() {
        val card = Card112.of(venue, grid, here, accuracyM = 12, ageMs = 0, walk = StraightLine)
        assertEquals("44.12345, 28.56789", card.coordinates)
        val round = Card112.of(venue, grid, GeoPoint(44.1, 28.5), 0, 0, StraightLine)
        assertEquals("44.10000, 28.50000", round.coordinates, "five decimals even when they are zeros")
        assertEquals("-0.00012, 3.40000", Card112.of(venue, grid, GeoPoint(-0.00012, 3.4), 0, 0, StraightLine).coordinates)
    }

    @Test
    fun theCardNamesTheSpotTheAccuracyThePlusCodeAndTheSquare() {
        val card = Card112.of(venue, grid, here, accuracyM = 12, ageMs = 0, walk = StraightLine)
        assertEquals("Beach, Please!", card.venueName)
        assertEquals("Plaja mare", card.zoneName)
        assertEquals(3, card.landmark)
        assertEquals(12, card.accuracyM)
        assertEquals(OpenLocationCode.encode(44.12345, 28.56789), card.plusCode)
        assertEquals(grid.cellAt(44.12345, 28.56789), card.gridCell)
        assertEquals("8GPC4HF9+95", card.plusCode)
        assertEquals(0, card.ageMin)
    }

    @Test
    fun theSentenceToReadIsTheVenueTheZoneAndTheLandmark() {
        val card = Card112.of(venue, grid, here, 12, 0, StraightLine)
        assertEquals("Sunt la Beach, Please!, Plaja mare, lângă Reperul 3", card.spoken("Sunt la Beach, Please!", "lângă Reperul 3"))

        val corner = GeoPoint(44.1295, 28.5790)
        assertTrue(venue.bounds.contains(corner.lat, corner.lon), "a corner of the site, in no zone")
        val inNoZone = Card112.of(venue, grid, corner, 0, 0, StraightLine)
        assertNull(inNoZone.zoneName)
        assertNull(inNoZone.landmark)
        assertNull(inNoZone.accuracyM, "no accuracy given is no accuracy claimed")
        assertEquals("Sunt la Beach, Please!", inNoZone.spoken("Sunt la Beach, Please!", "lângă Reperul 3"), "no zone, no landmark near: no words about them")
    }

    @Test
    fun outsideTheSiteTheCardNamesNoVenueAndNoFarAwayHelp() {
        val away = GeoPoint(44.2000, 28.7000)
        assertFalse(venue.bounds.contains(away.lat, away.lon))
        val card = Card112.of(venue, grid, away, 15, 0, StraightLine)
        assertNull(card.venueName)
        assertNull(card.spoken("Sunt la Beach, Please!", null), "not \"Sunt la\" a festival the person isn't at")
        assertNull(card.gridCell)
        assertNull(card.landline, "a phone kilometres away is no help")
        assertNull(card.medical)
        assertEquals("44.20000, 28.70000", card.coordinates, "the coordinates still say where the person is")
        assertEquals(OpenLocationCode.encode(44.2, 28.7), card.plusCode)

        // just outside the fence, help a few hundred metres away is still offered
        val nearGate = GeoPoint(44.1190, 28.5650)
        assertFalse(venue.bounds.contains(nearGate.lat, nearGate.lon))
        val close = Card112.of(venue, grid, nearGate, 15, 0, StraightLine)
        assertNull(close.venueName)
        assertEquals("Telefon la intrare", close.landline?.name)
        assertTrue(close.landline!!.meters <= Card112.HELP_MAX_M)
    }

    @Test
    fun theAccuracyIsTheFixsOwnHoweverLarge() {
        assertEquals(800, Card112.of(venue, grid, here, 800, 0, StraightLine).accuracyM, "not capped at what a report's byte holds")
    }

    @Test
    fun theNearestLandlineAndMedicalPointComeWithTheirDistance() {
        val card = Card112.of(venue, grid, here, 12, 0, StraightLine)
        val phone = card.landline!!
        assertEquals("Telefon la intrare", phone.name)
        assertEquals(distanceMeters(here, GeoPoint(44.1240, 28.5680)), phone.meters)
        assertTrue(phone.meters in 50..200, "about ${phone.meters} m")
        val medical = card.medical!!
        assertEquals("Cortul medical", medical.name, "a medical zone counts as a medical point")
        assertEquals(distanceMeters(here, GeoPoint(44.1250, 28.5700)), medical.meters)
    }

    @Test
    fun aPlaceNobodyCanWalkToIsNotOffered() {
        val blocked = WalkDistance { _, to -> if (to.lat == 44.1240) null else distanceMeters(here, to) }
        val card = Card112.of(venue, grid, here, 12, 0, blocked)
        assertEquals("Telefon departe", card.landline?.name, "the next one, not the one behind a fence")
        val none = Card112.of(venue, grid, here, 12, 0) { _, _ -> null }
        assertNull(none.landline)
        assertNull(none.medical)
    }

    @Test
    fun aVenueWithoutThoseTypesGivesACardWithoutThem() {
        val card = Card112.of(venue.copy(pois = emptyList(), zones = emptyList()), grid, here, 12, 0, StraightLine)
        assertNull(card.landline)
        assertNull(card.medical)
        assertEquals(null, card.zoneName)
    }

    @Test
    fun anOldPositionSaysHowOldItIs() {
        assertEquals(7, Card112.of(venue, grid, here, 12, 7 * 60_000L + 30_000L, StraightLine).ageMin)
        assertEquals(0, Card112.of(venue, grid, here, 12, -5_000L, StraightLine).ageMin, "a clock a little ahead is not the future")
    }
}
