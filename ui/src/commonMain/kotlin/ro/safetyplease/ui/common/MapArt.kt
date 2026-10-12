package ro.safetyplease.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.designsystem.AppColors
import ro.safetyplease.ui.designsystem.MEDICAL_ZONE

/** Map icons: filled, on a 24 grid, so they read white on small colored circles. */
object MapGlyph {
    val Stage by glyph("M3.5 17.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0Z M13.5 15.5a3 3 0 1 0 6 0a3 3 0 1 0-6 0Z M8 6.2L19.5 3.7V15.5H18V7.4L9.5 9.2V17.5H8Z")
    val Food by glyph(
        "M4 3h1.25v5.5h1V3h1.5v5.5h1V3H10v6a3 3 0 0 1-2.25 2.9V21h-1.5v-9.1A3 3 0 0 1 4 9Z " +
            "M15 3c2.8 1.2 4 4.6 4 9.5h-2.25V21H15Z",
    )
    val Bar by glyph("M4 4h16l-7 8.2V18h3v2H8v-2h3v-5.8Z")
    val Medical by glyph("M9.5 3.5h5v6h6v5h-6v6h-5v-6h-6v-5h6Z")
    val Entrance by glyph("M11 3h9v18h-9v-2h7V5h-7Z M3 11h7.2L7.6 8.4L9 7l5 5-5 5-1.4-1.4 2.6-2.6H3Z")
    val Exit by glyph("M4 3h9v4h-2V5H6v14h5v-2h2v4H4Z M15.4 7l5 5-5 5-1.4-1.4 2.6-2.6H9v-2h7.6L14 8.4Z")
    val Camping by glyph("M12 3.5L1.5 20.5h21Z M12 11.5l-4 9h8Z", evenOdd = true)
    val Chill by glyph("M12 2a5.5 5.5 0 0 0-5.3 7A4.5 4.5 0 0 0 7.5 18H11v4h2v-4h3.5a4.5 4.5 0 0 0 .8-9A5.5 5.5 0 0 0 12 2Z")
    val Water by glyph("M12 2.5C9 6.6 5.5 10.6 5.5 14.5a6.5 6.5 0 0 0 13 0C18.5 10.6 15 6.6 12 2.5Z")
    val Wc by glyph(
        "M5 4.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0Z M15 4.5a2 2 0 1 0 4 0a2 2 0 1 0-4 0Z M5 8h4l1 7H9v7H5v-7H4Z " +
            "M15 8h4l2 8h-2v6h-4v-6h-2Z M11.25 3h1.5v18h-1.5Z",
    )
    val Info by glyph("M10.75 10h2.5v9h-2.5Z M12 4.5a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5Z")
    val Charge by glyph("M13.5 2L5 13.5h5.5L9.5 22 19 9.5h-5.5Z")
    /** A heart with a lightning bolt cut out, as on defibrillator signs. */
    val Dea by glyph(
        "M12 21l-1.45-1.32C5.4 15.36 2 12.28 2 8.5C2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 " +
            "C19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54Z M13 6L8 13h3.2l-0.7 5.5L16 11h-3.2Z",
        evenOdd = true,
    )
    val Minus by glyph("M5 10.75h14v2.5H5Z")
    val Plus by glyph("M10.75 5h2.5v5.75H19v2.5h-5.75V19h-2.5v-5.75H5v-2.5h5.75Z")
}

private fun glyph(path: String, evenOdd: Boolean = false): Lazy<ImageVector> = lazy {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black), pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
        .build()
}

fun typeGlyph(type: String): ImageVector? = when (type) {
    "stage" -> MapGlyph.Stage
    "food" -> MapGlyph.Food
    "bar" -> MapGlyph.Bar
    "medical" -> MapGlyph.Medical
    "entrance" -> MapGlyph.Entrance
    "exit" -> MapGlyph.Exit
    "camping" -> MapGlyph.Camping
    "chill", Venue.SHADE -> MapGlyph.Chill
    "water" -> MapGlyph.Water
    "wc" -> MapGlyph.Wc
    "info" -> MapGlyph.Info
    "charge" -> MapGlyph.Charge
    Venue.DEA -> MapGlyph.Dea
    else -> null
}

// slots of the zone palette in Theme.kt
private const val SLATE = 0
private const val TEAL = 1
private const val SAND = 2
private const val OLIVE = 3
private const val STONE = 4
private const val PINE = 5
private const val MIST = 6

/**
 * Palette slot of a zone: by type, so the food court is always sand and the camping always olive;
 * without a type, by position. The medical zone gets first-aid green from the theme whatever the slot.
 */
fun zoneTone(venue: Venue, index: Int): Int = when (venue.zones[index].type) {
    "stage" -> if (venue.zones.indexOfFirst { it.type == "stage" } == index) SLATE else MIST
    "food" -> SAND
    "bar" -> TEAL
    "entrance" -> STONE
    "camping" -> OLIVE
    "chill" -> PINE
    else -> index
}

/** Solid color of a place, for the circle under its icon. */
fun spotColor(colors: AppColors, type: String): Color = when (type) {
    "medical", Venue.DEA -> colors.zoneSolid(SLATE, MEDICAL_ZONE)
    "exit" -> colors.zoneSolid(PINE)
    Venue.MEETING -> colors.orange
    "water" -> colors.zoneSolid(SLATE)
    "wc" -> colors.zoneSolid(STONE)
    "info" -> colors.zoneSolid(TEAL)
    "charge" -> colors.zoneSolid(SAND)
    else -> colors.accent
}
