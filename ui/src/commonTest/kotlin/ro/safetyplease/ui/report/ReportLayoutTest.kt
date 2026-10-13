package ro.safetyplease.ui.report

import kotlin.test.Test
import kotlin.test.assertEquals

class ReportLayoutTest {
    @Test
    fun categoriesDropToOneColumnWhenTheLongestWordWouldBreak() {
        assertEquals(2, categoryColumns(widestWordPx = 300, roomPerColumnPx = 300))
        assertEquals(1, categoryColumns(widestWordPx = 301, roomPerColumnPx = 300))
    }
}
