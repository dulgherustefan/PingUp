package ro.safetyplease.ui.map

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
}
