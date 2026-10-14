package ro.safetyplease.app.service

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.safetyplease.app.R
import ro.safetyplease.core.data.MyReport
import ro.safetyplease.core.incidents.Discreet
import ro.safetyplease.core.protocol.IncidentCategory
import ro.safetyplease.core.protocol.Severity
import ro.safetyplease.core.safety.Welfare

class ReportLookTest {
    private fun report(description: String) = MyReport(
        incidentId = "a1", category = IncidentCategory.HARASSMENT, severity = Severity.MEDIUM, zone = "", createdAt = 0, description = description,
    )

    @Test
    fun discreetAndUnwellReportsHearBackSilentlyAndNeverOnTheLockScreen() {
        for (description in listOf(Discreet.describe("Mă simt în nesiguranță"), Welfare.describe("Nu sunt bine"))) {
            val look = reportLook(report(description), R.string.notif_response_title)
            assertEquals(description, Notifier.CHANNEL_QUIET, look.channel)
            assertTrue(description, look.silent)
            assertEquals(description, NotificationCompat.VISIBILITY_SECRET, look.visibility)
            assertNull("the app's name, not what the report was about", look.title)
        }
    }

    @Test
    fun otherReportsSoundOnTheStatusChannel() {
        val look = reportLook(report("Cineva a leșinat"), R.string.notif_response_title)
        assertEquals(Notifier.CHANNEL_STATUS, look.channel)
        assertFalse(look.silent)
        assertEquals(R.string.notif_response_title, look.title)
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, look.visibility)
    }
}
