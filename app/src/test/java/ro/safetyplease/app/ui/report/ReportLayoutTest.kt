package ro.safetyplease.app.ui.report

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportLayoutTest {
    @Test
    fun categoriesDropToOneColumnWhenTheLongestWordWouldBreak() {
        assertEquals(2, categoryColumns(widestWordPx = 300, roomPerColumnPx = 300))
        assertEquals(1, categoryColumns(widestWordPx = 301, roomPerColumnPx = 300))
    }
}
