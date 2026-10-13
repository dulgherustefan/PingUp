package ro.safetyplease.ui.map

import androidx.compose.ui.geometry.Offset
import ro.safetyplease.core.announce.Evacuation
import ro.safetyplease.core.announce.MapState
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.event.SitePlan
import ro.safetyplease.core.venue.Bounds
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.core.venue.Zone
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapOverlayTest {
    private val venue = Venue(
        "test", Bounds(44.0, 26.0, 44.01, 26.01),
        listOf("a", "b", "c").map { Zone(it, it, "#336699", listOf(GeoPoint(44.0, 26.0), GeoPoint(44.0, 26.001), GeoPoint(44.001, 26.0))) },
    )
    private val here = GeoPoint(44.005, 26.005)

    private fun near(expected: Offset, actual: Offset) =
        assertTrue(abs(expected.x - actual.x) < 1e-3f && abs(expected.y - actual.y) < 1e-3f, "$expected, not $actual")

    @Test
    fun anImageLandsOnItsCorners() {
        // a 200 x 100 image turned a quarter: its top edge goes down the screen
        val m = imageMatrix(Offset(50f, 10f), Offset(50f, 210f), Offset(-50f, 10f), 200, 100)
        near(Offset(50f, 10f), m.map(Offset.Zero))
        near(Offset(50f, 210f), m.map(Offset(200f, 0f)))
        near(Offset(-50f, 10f), m.map(Offset(0f, 100f)))
        near(Offset(-50f, 210f), m.map(Offset(200f, 100f)))
    }

    @Test
    fun theConsolesStatesBecomeZoneStylesAndAnEvacuationCoversThem() {
        val state = MapState(mapOf("a" to ZoneState.CROWDED, "b" to ZoneState.CLOSED, "c" to ZoneState.EXIT_ONLY))
        assertEquals(mapOf("a" to ZoneStyle.CROWDED, "b" to ZoneStyle.CLOSED, "c" to ZoneStyle.EXIT_ONLY), MapOverlay.zoneStyles(state, venue))
        val evacuating = state.copy(evacuation = Evacuation(1, setOf("b")))
        assertEquals(ZoneStyle.EVACUATE, MapOverlay.zoneStyles(evacuating, venue)["b"])
        assertEquals(ZoneStyle.CROWDED, MapOverlay.zoneStyles(evacuating, venue)["a"])
        val everywhere = MapState(evacuation = Evacuation(null, emptySet()))
        assertEquals(venue.zones.associate { it.id to ZoneStyle.EVACUATE }, MapOverlay.zoneStyles(everywhere, venue))
        assertEquals(emptyMap(), MapOverlay.zoneStyles(MapState.OPEN, venue))
    }

    @Test
    fun layersMergeInTheOrderTheyCameAndALaterOneWins() {
        val overlays = MapOverlays()
        val plan = PlanImage(SitePlan(ByteArray(8), here, here, here, here))
        overlays.set(OverlayLayer.CONTROL, MapOverlay(zones = mapOf("a" to ZoneStyle.CLOSED), plan = plan))
        overlays.set(OverlayLayer.ROUTE, MapOverlay(routes = listOf(MapRoute(listOf(here, here))), zones = mapOf("a" to ZoneStyle.CROWDED)))
        val merged = overlays.overlay.value
        assertEquals(1, merged.routes.size)
        assertEquals(mapOf("a" to ZoneStyle.CROWDED), merged.zones)
        assertEquals(plan, merged.plan, "kept from the layer that has one")

        overlays.set(OverlayLayer.CONTROL, MapOverlay())
        assertEquals(MapOverlay(routes = merged.routes, zones = mapOf("a" to ZoneStyle.CROWDED)), overlays.overlay.value)
        overlays.clear(OverlayLayer.ROUTE)
        assertEquals(MapOverlay.EMPTY, overlays.overlay.value)
    }

    @Test
    fun aTapOnAPinGoesToItsLayer() {
        val overlays = MapOverlays()
        val tapped = mutableListOf<String>()
        overlays.set(OverlayLayer.REGASIRE, MapOverlay(pins = listOf(OverlayPin("ana", here, PinKind.LAST_SEEN, "Ana"))), onPinTap = { tapped += it.key })
        overlays.set(OverlayLayer.LAYERS, MapOverlay(pins = listOf(OverlayPin("wc-5", here, PinKind.SERVICE))))
        assertTrue(overlays.tap("ana"))
        assertTrue(overlays.tap("wc-5"), "a layer without a handler still owns its pin")
        assertFalse(overlays.tap("00112233"), "an incident's key is no overlay pin")
        assertEquals(listOf("ana"), tapped)
    }
}
