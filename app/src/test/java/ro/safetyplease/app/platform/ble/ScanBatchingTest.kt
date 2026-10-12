package ro.safetyplease.app.platform.ble

import org.junit.Assert.assertEquals
import org.junit.Test
import ro.safetyplease.core.mesh.PowerMode

class ScanBatchingTest {
    @Test
    fun scanIsBatchedOnlyWhereTheControllerQueuesResults() {
        assertEquals(BleConstants.SCAN_BATCH_DELAY_MS, scanReportDelay(PowerMode.BALANCED, offloadedBatching = true))
        assertEquals(BleConstants.SCAN_BATCH_DELAY_MS, scanReportDelay(PowerMode.LOW_POWER, offloadedBatching = true))
        assertEquals(0L, scanReportDelay(PowerMode.BALANCED, offloadedBatching = false))
    }

    @Test
    fun hurriedScanIsNeverBatched() {
        assertEquals(0L, scanReportDelay(PowerMode.LOW_LATENCY, offloadedBatching = true))
    }
}
