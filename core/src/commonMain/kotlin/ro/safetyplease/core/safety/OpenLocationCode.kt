package ro.safetyplease.core.safety

import kotlin.math.roundToLong

/**
 * Encoder of Open Location Code ("Plus Codes"), after the public specification: a point as ten digits of a 20-letter
 * alphabet, as four pairs-of-places refined to 14 m (8 digits, then "+", then 2), and up to five digits more that
 * split the cell in a 4 by 5 grid. Whole cells, so a code stands for an area, not a point.
 */
object OpenLocationCode {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"
    private const val SEPARATOR = '+'
    private const val SEPARATOR_AT = 8
    private const val PAIRS = 5
    private const val GRID_DIGITS = 5
    private const val PAIR_BASE = 20
    private const val GRID_ROWS = 5
    private const val GRID_COLUMNS = 4

    /** The finest steps per degree: 1/8000 degree for the pairs, then the grid's 5 rows and 4 columns five times over. */
    private const val LATITUDE_STEPS = 8_000L * 3_125
    private const val LONGITUDE_STEPS = 8_000L * 1_024

    const val DEFAULT_LENGTH = 10
    const val MAX_LENGTH = 15

    /** The code of the cell holding the point, [length] digits long: 2, 4, 6 or 8, or 10 to 15. */
    fun encode(latitude: Double, longitude: Double, length: Int = DEFAULT_LENGTH): String {
        require(latitude.isFinite() && longitude.isFinite())
        require(length in 2..MAX_LENGTH && (length >= DEFAULT_LENGTH || length % 2 == 0)) { "length $length" }
        var lat = cells((latitude.coerceIn(-90.0, 90.0) + 90.0) * LATITUDE_STEPS).coerceAtMost(180 * LATITUDE_STEPS - 1)
        val shifted = ((longitude + 180.0) % 360.0 + 360.0) % 360.0
        var lon = cells(shifted * LONGITUDE_STEPS).coerceAtMost(360 * LONGITUDE_STEPS - 1)

        val digits = CharArray(PAIRS * 2 + GRID_DIGITS)
        for (i in GRID_DIGITS - 1 downTo 0) {
            digits[PAIRS * 2 + i] = ALPHABET[(lat % GRID_ROWS * GRID_COLUMNS + lon % GRID_COLUMNS).toInt()]
            lat /= GRID_ROWS
            lon /= GRID_COLUMNS
        }
        for (i in PAIRS - 1 downTo 0) {
            digits[i * 2] = ALPHABET[(lat % PAIR_BASE).toInt()]
            digits[i * 2 + 1] = ALPHABET[(lon % PAIR_BASE).toInt()]
            lat /= PAIR_BASE
            lon /= PAIR_BASE
        }
        val kept = digits.concatToString(0, length)
        return if (length >= SEPARATOR_AT) {
            kept.take(SEPARATOR_AT) + SEPARATOR + kept.drop(SEPARATOR_AT)
        } else {
            kept.padEnd(SEPARATOR_AT, '0') + SEPARATOR
        }
    }

    /** Rounding to a millionth of a step first, so a value a hair under a cell's edge doesn't fall into the cell below. */
    private fun cells(steps: Double): Long = (steps * 1e6).roundToLong() / 1_000_000L
}
