package ro.safetyplease.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLabelsTest {
    private val clean = MeasuredName(40f, 28f, true)
    private val cut = MeasuredName(40f, 28f, false)

    private fun choose(width: Float, height: Float, icon: Float = 20f, stacked: MeasuredName = clean, beside: MeasuredName = clean) =
        chooseZoneLabel(width, height, icon, gap = 3f, minWidth = 44f, stacked = stacked, beside = beside)

    @Test
    fun aRoomWithSpaceForIconAndNameStacksThem() {
        assertEquals(ZoneLabelMode.Stacked, choose(80f, 60f))
    }

    @Test
    fun aShortRoomPutsTheNameBesideTheIcon() {
        assertEquals(ZoneLabelMode.Beside, choose(80f, 30f, beside = MeasuredName(50f, 14f, true)))
    }

    @Test
    fun belowTheMinimumWidthOnlyTheIconStays() {
        assertEquals(ZoneLabelMode.IconOnly, choose(43f, 100f))
        assertEquals(ZoneLabelMode.None, choose(43f, 100f, icon = 0f))
        assertEquals(ZoneLabelMode.Stacked, choose(44f, 100f))
    }

    @Test
    fun aNameThatWouldBeCutNeverShows() {
        assertEquals(ZoneLabelMode.IconOnly, choose(80f, 60f, stacked = cut, beside = cut))
        assertEquals(ZoneLabelMode.None, choose(80f, 60f, icon = 0f, stacked = cut))
    }

    @Test
    fun withoutAnIconTheNameAloneNeedsOnlyItsOwnHeight() {
        assertEquals(ZoneLabelMode.TextOnly, choose(80f, 30f, icon = 0f))
        assertEquals(ZoneLabelMode.None, choose(80f, 20f, icon = 0f))
    }

    @Test
    fun aLineEndingInsideAWordIsABrokenWord() {
        val name = "Chill-out area"
        assertTrue(breaksWord(name, listOf(7, name.length)), "Chill-o / ut area")
        assertFalse(breaksWord(name, listOf(6, name.length)), "after the hyphen")
        assertFalse(breaksWord(name, listOf(10, name.length)), "at the space")
        assertFalse(breaksWord(name, listOf(name.length)), "one line")
        assertFalse(breaksWord(name, listOf(name.length, name.length)), "the last line ends the text")
    }

    private val within = Rect(0f, 0f, 200f, 100f)
    private val block = Size(60f, 30f)

    @Test
    fun aClearBlockStaysWhereItIsWantedAndInsideItsZone() {
        assertEquals(Offset(70f, 35f), placeClear(block, Offset(70f, 35f), within, emptyList()))
        assertEquals(Offset(140f, 70f), placeClear(block, Offset(190f, 95f), within, emptyList()), "pulled inside")
    }

    @Test
    fun aBlockUnderAControlMovesToTheNearestFreeSpot() {
        val control = Rect(150f, 45f, 200f, 100f)
        val spot = placeClear(block, Offset(140f, 70f), within, listOf(control))!!
        assertFalse(Rect(spot, block).overlaps(control))
        assertTrue(spot.x >= within.left && spot.y >= within.top && spot.x + block.width <= within.right && spot.y + block.height <= within.bottom)
        assertEquals(Offset(90f, 70f), spot, "left of the control, same row")
    }

    @Test
    fun noRoomMeansNoPlace() {
        assertNull(placeClear(Size(300f, 30f), Offset.Zero, within, emptyList()))
        assertNull(placeClear(block, Offset(70f, 35f), within, listOf(within)))
    }

    @Test
    fun cornersAreMeasuredFromTheirOwnEdges() {
        val canvas = Size(400f, 300f)
        assertEquals(Rect(10f, 210f, 70f, 290f), cornerRect(MapCorner.BottomStart, canvas, 60f, 80f, 10f, 10f))
        assertEquals(Rect(330f, 220f, 400f, 300f), cornerRect(MapCorner.BottomEnd, canvas, 70f, 80f))
        assertEquals(Rect(16f, 14f, 76f, 44f), cornerRect(MapCorner.TopStart, canvas, 60f, 30f, 16f, 14f))
    }

    @Test
    fun theScaleBarMovesToTheFirstCornerNoControlCovers() {
        val canvas = Size(400f, 300f)
        val corners = listOf(MapCorner.BottomStart, MapCorner.BottomEnd, MapCorner.TopStart).map { cornerRect(it, canvas, 60f, 20f, 16f, 11f) }
        assertEquals(corners[0], firstFree(corners, emptyList()))
        assertEquals(corners[1], firstFree(corners, listOf(corners[0])))
        assertEquals(corners[2], firstFree(corners, listOf(corners[0], corners[1])))
        assertNotNull(firstFree(corners, corners), "all covered: the usual corner")
        assertEquals(corners[0], firstFree(corners, corners))
    }
}
