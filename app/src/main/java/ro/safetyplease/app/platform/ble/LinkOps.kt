package ro.safetyplease.app.platform.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertisingSetCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** One GATT operation in flight per link; the stack's callback completes it through [finish]. */
class GattOps<K : Any> {
    private var op: K? = null
    private var signal: CompletableDeferred<Boolean>? = null

    /** The link dropped: the in-flight operation and every later one fail immediately. */
    var dropped = false
        private set

    suspend fun run(kind: K, timeoutMs: Long, start: () -> Boolean): Boolean {
        // the stack accepts requests on a dropped link too; without this we'd wait out the full timeout
        if (dropped) return false
        val done = CompletableDeferred<Boolean>()
        op = kind
        signal = done
        val started = runCatching(start).getOrDefault(false)
        val ok = started && (withTimeoutOrNull(timeoutMs) { done.await() } ?: false)
        if (signal === done) {
            signal = null
            op = null
        }
        return ok && !dropped
    }

    fun finish(kind: K, ok: Boolean) {
        if (op == kind) signal?.complete(ok)
    }

    fun drop() {
        dropped = true
        signal?.complete(false)
    }
}

/** 143 (GATT_CONNECTION_CONGESTED): the frame is queued, but the next one would be dropped if sent right away. */
fun sendAccepted(status: Int): Boolean =
    status == BluetoothGatt.GATT_SUCCESS || status == BluetoothGatt.GATT_CONNECTION_CONGESTED

/** Advertising start failures in a row; after a few, the phone acts as a leaf until starting works again. */
class AdvertiseFailures {
    var count = 0
        private set

    val demoted get() = count >= BleConstants.ADVERTISE_DEMOTE_AFTER

    /** How long to wait before retrying after a start failed with [status]. */
    fun failed(status: Int): Long {
        if (status != AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED) count++
        return if (demoted) BleConstants.ADVERTISE_DEMOTED_RETRY_MS else BleConstants.ADVERTISE_RETRY_MS
    }

    fun reset() {
        count = 0
    }
}
