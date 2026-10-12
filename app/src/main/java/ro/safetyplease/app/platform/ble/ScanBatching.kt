package ro.safetyplease.app.platform.ble

import ro.safetyplease.core.mesh.PowerMode

/**
 * Batches where the controller queues results itself, so the phone wakes once per batch, not once per advertisement.
 * LOW_LATENCY is the hurry mode (right after a report): there every result comes as soon as it is heard.
 */
fun scanReportDelay(mode: PowerMode, offloadedBatching: Boolean): Long =
    if (offloadedBatching && mode != PowerMode.LOW_LATENCY) BleConstants.SCAN_BATCH_DELAY_MS else 0L
