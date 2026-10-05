package ro.safetyplease.app.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.le.AdvertisingSetCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** O singura operatie GATT in zbor pe o legatura; callback-ul stivei o incheie prin [finish]. */
class GattOps<K : Any> {
    private var op: K? = null
    private var signal: CompletableDeferred<Boolean>? = null

    /** Legatura a picat: operatia in zbor si toate cele urmatoare esueaza imediat. */
    var dropped = false
        private set

    suspend fun run(kind: K, timeoutMs: Long, start: () -> Boolean): Boolean {
        // stiva accepta cereri si pe o legatura picata; fara asta asteptam timeout-ul intreg
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

/** 143 (GATT_CONNECTION_CONGESTED): cadrul e in coada, dar urmatorul ar fi aruncat daca vine imediat. */
fun sendAccepted(status: Int): Boolean =
    status == BluetoothGatt.GATT_SUCCESS || status == BluetoothGatt.GATT_CONNECTION_CONGESTED

/** Esecuri de pornire a advertising-ului la rand; dupa cateva, telefonul se poarta ca frunza pana merge din nou. */
class AdvertiseFailures {
    var count = 0
        private set

    val demoted get() = count >= BleConstants.ADVERTISE_DEMOTE_AFTER

    /** Cat asteptam pana la urmatoarea incercare dupa un start esuat cu [status]. */
    fun failed(status: Int): Long {
        if (status != AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED) count++
        return if (demoted) BleConstants.ADVERTISE_DEMOTED_RETRY_MS else BleConstants.ADVERTISE_RETRY_MS
    }

    fun reset() {
        count = 0
    }
}
