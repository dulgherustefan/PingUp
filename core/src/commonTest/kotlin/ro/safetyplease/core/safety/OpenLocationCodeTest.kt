package ro.safetyplease.core.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Vectors of the specification's public test data (encoding.csv of github.com/google/open-location-code), written out
 * from it, with the codes of the points on the edges of the world and of the date line.
 */
class OpenLocationCodeTest {
    private class Vector(val code: String, val lat: Double, val lon: Double, val length: Int)

    private val vectors = listOf(
        Vector("7FG49Q00+", 20.375, 2.775, 6),
        Vector("7FG49QCJ+2V", 20.3700625, 2.7821875, 10),
        Vector("7FG49QCJ+2VX", 20.3701125, 2.782234375, 11),
        Vector("8FVC2222+22", 47.0000625, 8.0000625, 10),
        Vector("4VCPPQGP+Q9", -41.2730625, 174.7859375, 10),
        Vector("62G20000+", 0.5, -179.5, 4),
        Vector("22220000+", -89.5, -179.5, 4),
        Vector("7FG40000+", 20.5, 2.5, 4),
        Vector("22222222+22", -89.9999375, -179.9999375, 10),
        Vector("6VGX0000+", 0.5, 179.5, 4),
        Vector("6FH32222+222", 1.0, 1.0, 11),
        // past eleven digits, down to the finest grid
        Vector("7FG49QCJ+2VXGJ", 20.3701135, 2.78223535156, 13),
        Vector("849VGJQF+VX7QR3J", 37.539669125, -122.375069724, 15),
        // decimals a double can't hold exactly, which a plain floor puts in the cell below
        Vector("6FH56C22+22", 1.2, 3.4, 10),
        Vector("8F75J22M+26", 35.6, 3.033, 10),
        // the north pole is clipped into the last row, and the date line wraps
        Vector("CFX30000+", 90.0, 1.0, 4),
        Vector("62H20000+", 1.0, 180.0, 4),
        Vector("62H30000+", 1.0, 181.0, 4),
    )

    @Test
    fun theSpecificationsVectorsEncodeToTheirCodes() {
        for (v in vectors) assertEquals(v.code, OpenLocationCode.encode(v.lat, v.lon, v.length), "${v.lat}, ${v.lon} in ${v.length}")
    }

    @Test
    fun theDefaultIsTenDigitsAroundFourteenMetres() {
        assertEquals("7FG49QCJ+2V", OpenLocationCode.encode(20.3700625, 2.7821875))
        assertEquals(11, OpenLocationCode.encode(44.1, 28.6).length, "ten digits and the plus")
    }

    @Test
    fun theWorldsCornersStayInsideTheCodeSpace() {
        assertEquals("22222222+22", OpenLocationCode.encode(-90.0, -180.0))
        assertEquals("C2X2X2X2+X2", OpenLocationCode.encode(90.0, 180.0), "the pole is in the last row, the date line in the first column")
        assertEquals("CVXFXRX2+X2", OpenLocationCode.encode(95.5, -190.2), "out of range latitude is clipped, longitude wraps")
        assertEquals(OpenLocationCode.encode(-45.0, -180.0), OpenLocationCode.encode(-45.0, 180.0), "180 is -180")
        assertEquals(OpenLocationCode.encode(10.0, 20.0), OpenLocationCode.encode(10.0, 380.0), "a full turn east")
    }

    @Test
    fun aLengthTheSpecificationDoesNotKnowIsRefused() {
        for (length in listOf(0, 1, 3, 7, 9)) assertFailsWith<IllegalArgumentException> { OpenLocationCode.encode(1.0, 1.0, length) }
        assertFailsWith<IllegalArgumentException> { OpenLocationCode.encode(Double.NaN, 1.0) }
    }

    @Test
    fun aLengthPastFifteenGivesTheFifteenDigitCode() {
        for (length in listOf(16, 20, 100)) {
            assertEquals("849VGJQF+VX7QR3J", OpenLocationCode.encode(37.539669125, -122.375069724, length), "length $length")
        }
    }

    @Test
    fun twoPointsInTheSameCellShareACode() {
        assertEquals(OpenLocationCode.encode(44.12345, 28.56789), OpenLocationCode.encode(44.123452, 28.567893))
    }
}
