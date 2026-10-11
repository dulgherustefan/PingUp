package ro.safetyplease.core.venue

import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * The site plan's square grid over the venue bounds: columns A, B, C... from west to east, rows 1, 2, 3... from north
 * to south, so "D4" names a square on the radio. The width and height are split evenly into cells of about
 * [CELL_METERS]; the grid is computed on the console, so the reports don't change.
 */
data class SiteGrid(val bounds: Bounds, val columns: Int, val rows: Int) {
    init {
        require(columns in 1..MAX_COLUMNS && rows in 1..MAX_ROWS)
    }

    /** The square holding the point; null outside the venue. */
    fun cellAt(lat: Double, lon: Double): String? {
        if (!bounds.contains(lat, lon)) return null
        val column = ((lon - bounds.minLon) / (bounds.maxLon - bounds.minLon) * columns).toInt().coerceAtMost(columns - 1)
        val row = ((bounds.maxLat - lat) / (bounds.maxLat - bounds.minLat) * rows).toInt().coerceAtMost(rows - 1)
        return columnLabel(column) + rowLabel(row)
    }

    fun columnLabel(column: Int): String = ('A' + column).toString()

    fun rowLabel(row: Int): String = (row + 1).toString()

    /** Longitude of the west edge of [column]; [columns] gives the east edge of the site. */
    fun columnLon(column: Int): Double = bounds.minLon + (bounds.maxLon - bounds.minLon) * column / columns

    /** Latitude of the north edge of [row]; [rows] gives the south edge of the site. */
    fun rowLat(row: Int): Double = bounds.maxLat - (bounds.maxLat - bounds.minLat) * row / rows

    companion object {
        const val CELL_METERS = 50

        /** One letter per column. */
        const val MAX_COLUMNS = 26
        const val MAX_ROWS = 99

        fun of(bounds: Bounds, cellMeters: Int = CELL_METERS): SiteGrid {
            val metersPerDegree = 111_320.0
            val width = (bounds.maxLon - bounds.minLon) * metersPerDegree * cos(Math.toRadians((bounds.minLat + bounds.maxLat) / 2))
            val height = (bounds.maxLat - bounds.minLat) * metersPerDegree
            return SiteGrid(
                bounds,
                (width / cellMeters).roundToInt().coerceIn(1, MAX_COLUMNS),
                (height / cellMeters).roundToInt().coerceIn(1, MAX_ROWS),
            )
        }
    }
}
