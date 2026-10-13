package ro.safetyplease.ui.map

import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Spot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapLayersTest {
    @Test
    fun thePlanChipGoesSolidSeeThroughHidden() {
        assertEquals(PlanShade.FAINT, PlanShade.FULL.next())
        assertEquals(PlanShade.HIDDEN, PlanShade.FAINT.next())
        assertEquals(PlanShade.FULL, PlanShade.HIDDEN.next())
        assertNull(PlanShade.HIDDEN.opacity)
    }

    @Test
    fun switchingALayerOffDropsItsPickedPlace() {
        val layers = MapLayers()
        layers.toggle("water")
        layers.toggle("wc")
        layers.picked = LayerPick.Service("svc:water:8")
        layers.toggle("wc")
        assertEquals(LayerPick.Service("svc:water:8"), layers.picked)
        layers.toggle("water")
        assertNull(layers.picked)
        assertEquals(emptySet(), layers.types)
    }

    @Test
    fun theRouteTakesTheTargetOnce() {
        val layers = MapLayers()
        val spot = Spot("water", "Apă", GeoPoint(44.0, 28.0))
        layers.routeTo(spot)
        assertEquals(spot, layers.routeTarget.value)
        layers.routeTaken()
        assertNull(layers.routeTarget.value)
    }
}
