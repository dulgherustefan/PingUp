package ro.safetyplease.core.incidents

import ro.safetyplease.core.readText
import ro.safetyplease.core.repoFile
import ro.safetyplease.core.venue.Venue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscreetTest {
    @Test
    fun theMarkOpensTheDescriptionAndNothingElseCounts() {
        val description = Discreet.describe("Mă simt în nesiguranță")
        assertTrue(Discreet.isDiscreet(description))
        assertTrue(description.endsWith("Mă simt în nesiguranță"), "a staff phone that doesn't know it shows the words")
        assertFalse(Discreet.isDiscreet("cineva mă urmărește"))
        assertFalse(Discreet.isDiscreet("cineva\n" + Discreet.MARK), "only the first line says it")
        assertFalse(Discreet.isDiscreet(""))
    }

    @Test
    fun aVenueOffersSafeguardingOnlyWhenItsFileSaysSo() {
        val base = """{"name":"X","bounds":{"minLat":0,"minLon":0,"maxLat":1,"maxLon":1},"zones":[]"""
        assertFalse(Venue.parse("$base}").safeguarding)
        assertTrue(Venue.parse("$base,\"safeguarding\":true}").safeguarding)
        assertEquals(true, Venue.parse(repoFile("app/src/main/assets/venue.json").readText()).safeguarding, "the bundled layout has a team to answer")
    }
}
