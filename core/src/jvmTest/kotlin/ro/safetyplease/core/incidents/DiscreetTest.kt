package ro.safetyplease.core.incidents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.core.venue.Venue
import java.io.File

class DiscreetTest {
    @Test
    fun theMarkOpensTheDescriptionAndNothingElseCounts() {
        val description = Discreet.describe("Mă simt în nesiguranță")
        assertTrue(Discreet.isDiscreet(description))
        assertTrue("a staff phone that doesn't know it shows the words", description.endsWith("Mă simt în nesiguranță"))
        assertFalse(Discreet.isDiscreet("cineva mă urmărește"))
        assertFalse("only the first line says it", Discreet.isDiscreet("cineva\n" + Discreet.MARK))
        assertFalse(Discreet.isDiscreet(""))
    }

    @Test
    fun aVenueOffersSafeguardingOnlyWhenItsFileSaysSo() {
        val base = """{"name":"X","bounds":{"minLat":0,"minLon":0,"maxLat":1,"maxLon":1},"zones":[]"""
        assertFalse(Venue.parse("$base}").safeguarding)
        assertTrue(Venue.parse("$base,\"safeguarding\":true}").safeguarding)
        assertEquals("the bundled layout has a team to answer", true, Venue.parse(File("../app/src/main/assets/venue.json").readText()).safeguarding)
    }
}
