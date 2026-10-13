package ro.safetyplease.ui.text

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The names strings.xml holds, as java.text gives them for ro-RO and en-US. */
internal val Romanian = DateNames(
    months = listOf("ianuarie", "februarie", "martie", "aprilie", "mai", "iunie", "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie"),
    shortMonths = listOf("ian.", "feb.", "mar.", "apr.", "mai", "iun.", "iul.", "aug.", "sept.", "oct.", "nov.", "dec."),
    days = listOf("luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică"),
    shortDays = listOf("lun.", "mar.", "mie.", "joi", "vin.", "sâm.", "dum."),
)

internal val English = DateNames(
    months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"),
    shortMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"),
    days = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"),
    shortDays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
)

class DateTextTest {
    private val zone = TimeZone.of("Europe/Bucharest")
    private val monday = LocalDateTime(2026, 10, 12, 14, 30).toInstant(zone).toEpochMilliseconds()

    @Test
    fun thePatternsReadAsTheAndroidAppWroteThem() {
        assertEquals("12 octombrie, 14:30", formatDate("d MMMM, HH:mm", monday, Romanian, zone))
        assertEquals("12 oct., 14:30", formatDate("d MMM, HH:mm", monday, Romanian, zone))
        assertEquals("luni, 12 octombrie", formatDate("EEEE, d MMMM", monday, Romanian, zone))
        assertEquals("lun.", formatDate("EEE", monday, Romanian, zone))
        assertEquals("October 12, 14:30", formatDate("MMMM d, HH:mm", monday, English, zone))
        assertEquals("Oct 12", formatDate("MMM d", monday, English, zone))
        assertEquals("Monday, October 12", formatDate("EEEE, MMMM d", monday, English, zone))
    }

    @Test
    fun numbersAndQuotedTextKeepTheirShape() {
        val morning = LocalDateTime(2026, 3, 5, 7, 4).toInstant(zone).toEpochMilliseconds()
        assertEquals("05.03.2026 07:04", formatDate("dd.MM.yyyy HH:mm", morning, Romanian, zone))
        assertEquals("5/3/26 at 7:4", formatDate("d/M/yy 'at' H:m", morning, English, zone))
        assertEquals("it's 07", formatDate("'it''s' HH", morning, English, zone))
    }

    @Test
    fun theClockAndTheDaysFollowThePhonesZone() {
        assertEquals(1, Labels.daysBetween(monday - 15 * 3_600_000L, monday))
        assertEquals(0, Labels.daysBetween(monday - 3_600_000L, monday))
    }
}
