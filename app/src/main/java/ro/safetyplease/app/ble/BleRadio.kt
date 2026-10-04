package ro.safetyplease.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import ro.safetyplease.app.core.nodePrefix
import ro.safetyplease.app.mesh.PowerMode
import ro.safetyplease.app.mesh.Radio
import ro.safetyplease.app.mesh.RadioEvent
import ro.safetyplease.app.mesh.RadioStatus
import ro.safetyplease.app.protocol.NodeFlags
import java.nio.ByteBuffer

/**
 * Radioul BLE: fiecare telefon e simultan peripheral (GATT server + advertising) si central
 * (scanare + clienti GATT). Clientul scrie fara raspuns in caracteristica, serverul raspunde prin notificari.
 * Toata starea e atinsa doar din contextul lui [scope]; callback-urile Android sunt mutate acolo prin [post].
 */
@SuppressLint("MissingPermission")
class BleRadio(
    private val context: Context,
    private val scope: CoroutineScope,
    nodeId: Long,
    private val log: (String) -> Unit,
) : Radio {
    override val events = Channel<RadioEvent>(Channel.UNLIMITED)

    private enum class Op { CONNECT, MTU, DISCOVER, DESCRIPTOR, WRITE }

    private inner class OutLink(val id: Int, val address: String) {
        var gatt: BluetoothGatt? = null
        var characteristic: BluetoothGattCharacteristic? = null
        var mtu = BleConstants.DEFAULT_MTU
        var ready = false
        var closed = false
        var op: Op? = null
        var signal: CompletableDeferred<Boolean>? = null
        var setupJob: Job? = null
        val mutex = Mutex()
    }

    private class InLink(val id: Int, val device: BluetoothDevice, val mtu: Int)

    private val prefix = nodeId.nodePrefix()
    private val manager: BluetoothManager? = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private val tasks = Channel<() -> Unit>(Channel.UNLIMITED)

    private var running = false
    private var active = false
    private var jobs: List<Job> = emptyList()
    private var nextLinkId = 1

    private val outgoing = HashMap<String, OutLink>()
    private val incoming = HashMap<String, InLink>()
    private val scanned = HashMap<String, BluetoothDevice>()
    private val lastReported = HashMap<String, Long>()
    private val serverMtu = HashMap<String, Int>()

    private var gattServer: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var serviceReady = false
    private val notifyMutex = Mutex()
    private var notifySignal: CompletableDeferred<Boolean>? = null
    private var notifyAddress: String? = null

    private var flags = NodeFlags.ACCEPTS_CONNECTIONS
    private var scanMode = PowerMode.BALANCED
    private var advertiseMode = PowerMode.BALANCED

    private var scanning = false
    private var scanJob: Job? = null
    private var scanStartedAt = 0L
    private var lastScanStartAt = -BleConstants.SCAN_START_SPACING_MS
    private var lastScanResultAt = 0L
    private var scanBlockedUntil = 0L

    private var advertising = false
    private var canAdvertise = true
    private var advertiseJob: Job? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertiseBlockedUntil = 0L

    private var lastStatus: RadioStatus? = null
    private var waitingReason: String? = null

    private fun now() = SystemClock.elapsedRealtime()

    private fun post(block: () -> Unit) {
        tasks.trySend(block)
    }

    // --- ciclu de viata ---

    fun start() {
        if (running) return
        running = true
        ContextCompat.registerReceiver(
            context, stateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        jobs = listOf(
            scope.launch { for (task in tasks) task() },
            scope.launch {
                while (isActive) {
                    delay(5_000)
                    maintain()
                }
            },
        )
        post { bringUp() }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(stateReceiver) }
        tearDown()
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    /** De chemat dupa ce utilizatorul acorda permisiunile, ca radioul sa porneasca fara restart. */
    fun retry() = post { if (running && !active) bringUp() }

    fun hasPermissions(): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_ADVERTISE) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> post { if (running) bringUp() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> post { tearDown() }
            }
        }
    }

    private fun bringUp() {
        if (active) return
        val adapter = adapter
        if (adapter == null || !adapter.isEnabled || !hasPermissions()) {
            val reason = "radio in asteptare: bluetooth=${adapter?.isEnabled} permisiuni=${hasPermissions()}"
            if (reason != waitingReason) log(reason)
            waitingReason = reason
            publishStatus()
            return
        }
        waitingReason = null
        active = true
        canAdvertise = adapter.bluetoothLeAdvertiser != null && adapter.isMultipleAdvertisementSupported
        log("radio pornit, advertising ${if (canAdvertise) "suportat" else "NESUPORTAT (telefon frunza)"}")
        if (!openServer()) log("GATT server indisponibil")
        requestScan()
        publishStatus()
    }

    private fun tearDown() {
        if (!active) {
            publishStatus()
            return
        }
        active = false
        scanJob?.cancel()
        advertiseJob?.cancel()
        stopScanNow()
        stopAdvertisingNow()
        for (link in outgoing.values.toList()) closeOut(link, failed = false)
        for (link in incoming.values.toList()) {
            incoming.remove(link.device.address)
            events.trySend(RadioEvent.LinkDown(link.id))
        }
        runCatching { gattServer?.close() }
        gattServer = null
        characteristic = null
        serviceReady = false
        notifySignal?.complete(false)
        scanned.clear()
        serverMtu.clear()
        log("radio oprit")
        publishStatus()
    }

    private fun maintain() {
        if (!running) return
        if (!active) {
            bringUp()
            return
        }
        val now = now()
        val stale = scanning && now - scanStartedAt > BleConstants.SCAN_RESTART_MS
        val silent = scanning && now - maxOf(lastScanResultAt, scanStartedAt) > BleConstants.SCAN_SILENCE_RESTART_MS
        if ((!scanning || stale || silent) && scanJob?.isActive != true) requestScan()
        if (canAdvertise && serviceReady && !advertising && advertiseJob?.isActive != true) requestAdvertise()
        // adresele MAC se rotesc; ce nu a mai fost vazut de un minut nu mai e de folos
        val gone = lastReported.filterValues { now - it > 60_000 }.keys
        for (address in gone) {
            lastReported.remove(address)
            if (!outgoing.containsKey(address)) scanned.remove(address)
        }
        publishStatus()
    }

    private fun publishStatus() {
        val status = RadioStatus(active, scanning, advertising, canAdvertise)
        if (status == lastStatus) return
        lastStatus = status
        events.trySend(RadioEvent.Status(status))
    }

    // --- Radio ---

    override fun setAdvertisedFlags(flags: Int) {
        if (this.flags == flags) return
        this.flags = flags
        if (active && serviceReady) requestAdvertise()
    }

    override fun setPowerMode(scan: PowerMode, advertise: PowerMode) {
        val scanChanged = scan != scanMode
        val advertiseChanged = advertise != advertiseMode
        scanMode = scan
        advertiseMode = advertise
        if (!active) return
        if (scanChanged) requestScan()
        if (advertiseChanged && serviceReady) requestAdvertise()
    }

    override fun connect(address: String) {
        val adapter = adapter
        if (!active || adapter == null || outgoing.containsKey(address)) {
            events.trySend(RadioEvent.ConnectFailed(address))
            return
        }
        val device = scanned[address] ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            events.trySend(RadioEvent.ConnectFailed(address))
            return
        }
        val link = OutLink(nextLinkId++, address)
        outgoing[address] = link
        link.setupJob = scope.launch {
            val ok = withTimeoutOrNull(BleConstants.SETUP_TIMEOUT_MS) { setUp(link, device) } ?: false
            if (ok) {
                link.ready = true
                events.trySend(RadioEvent.LinkUp(link.id, address, true, link.mtu - BleConstants.ATT_OVERHEAD))
            } else if (!link.closed) {
                log("conectare esuata ${address.takeLast(5)}")
                closeOut(link, failed = true)
            }
        }
    }

    override fun disconnect(link: Int) {
        outgoing.values.firstOrNull { it.id == link }?.let { return closeOut(it, failed = false) }
        incoming.values.firstOrNull { it.id == link }?.let { closeIn(it) }
    }

    override suspend fun send(link: Int, frame: ByteArray): Boolean {
        outgoing.values.firstOrNull { it.id == link }?.let { return write(it, frame) }
        incoming.values.firstOrNull { it.id == link }?.let { return notify(it, frame) }
        return false
    }

    // --- central: clienti GATT ---

    private suspend fun OutLink.run(kind: Op, timeoutMs: Long, start: () -> Boolean): Boolean {
        val done = CompletableDeferred<Boolean>()
        op = kind
        signal = done
        val started = runCatching(start).getOrDefault(false)
        val ok = started && (withTimeoutOrNull(timeoutMs) { done.await() } ?: false)
        if (signal === done) {
            signal = null
            op = null
        }
        return ok
    }

    private fun OutLink.finish(kind: Op, ok: Boolean) {
        if (op == kind) signal?.complete(ok)
    }

    private suspend fun setUp(link: OutLink, device: BluetoothDevice): Boolean {
        val callback = ClientCallback(link)
        if (!link.run(Op.CONNECT, BleConstants.SETUP_TIMEOUT_MS) {
                // varianta noua cu BluetoothGattConnectionSettings exista doar pe versiunile recente de Android
                @Suppress("DEPRECATION")
                link.gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                link.gatt != null
            }) return false
        val gatt = link.gatt ?: return false
        // unele stive pierd prima operatie daca vine imediat dupa conectare
        delay(150)
        if (!link.run(Op.MTU, BleConstants.OPERATION_TIMEOUT_MS) { gatt.requestMtu(BleConstants.REQUESTED_MTU) }) {
            log("MTU nenegociat cu ${link.address.takeLast(5)}")
            return false
        }
        if (!link.run(Op.DISCOVER, BleConstants.OPERATION_TIMEOUT_MS) { gatt.discoverServices() }) return false
        val ch = gatt.getService(BleConstants.SERVICE)?.getCharacteristic(BleConstants.CHARACTERISTIC)
        val cccd = ch?.getDescriptor(BleConstants.CCCD)
        if (ch == null || cccd == null) {
            log("serviciul lipseste la ${link.address.takeLast(5)}")
            return false
        }
        link.characteristic = ch
        if (!gatt.setCharacteristicNotification(ch, true)) return false
        return link.run(Op.DESCRIPTOR, BleConstants.OPERATION_TIMEOUT_MS) {
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }
    }

    private suspend fun write(link: OutLink, frame: ByteArray): Boolean = link.mutex.withLock {
        val gatt = link.gatt
        val ch = link.characteristic
        if (!link.ready || link.closed || gatt == null || ch == null) return false
        // stiva refuza o scriere cat timp precedenta e inca in zbor; reincercam scurt
        repeat(5) {
            var accepted = false
            val ok = link.run(Op.WRITE, BleConstants.WRITE_TIMEOUT_MS) {
                accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    ch.value = frame
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(ch)
                }
                accepted
            }
            if (accepted) return ok
            if (link.closed) return false
            delay(25)
        }
        false
    }

    private fun closeOut(link: OutLink, failed: Boolean) {
        if (link.closed) return
        link.closed = true
        outgoing.remove(link.address)
        link.signal?.complete(false)
        if (!failed) link.setupJob?.cancel()
        val gatt = link.gatt
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            // close() imediat dupa disconnect() poate lasa legatura agatata in controller
            scope.launch {
                delay(400)
                runCatching { gatt.close() }
            }
        }
        if (link.ready) events.trySend(RadioEvent.LinkDown(link.id)) else events.trySend(RadioEvent.ConnectFailed(link.address))
    }

    private inner class ClientCallback(private val link: OutLink) : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = post {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                link.finish(Op.CONNECT, true)
            } else {
                if (!link.closed) log("deconectat ${link.address.takeLast(5)} status=$status")
                if (link.ready) closeOut(link, failed = false) else link.signal?.complete(false)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = post {
            if (status == BluetoothGatt.GATT_SUCCESS) link.mtu = mtu
            link.finish(Op.MTU, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = post {
            link.finish(Op.DISCOVER, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = post {
            link.finish(Op.DESCRIPTOR, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = post {
            link.finish(Op.WRITE, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            received(value)
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            // sub API 33 valoarea sta in obiectul caracteristicii si e suprascrisa de notificarea urmatoare
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) received(ch.value?.clone() ?: return)
        }

        private fun received(value: ByteArray) = post {
            if (link.closed || !link.ready) return@post
            // o notificare goala e semnalul serverului ca a inchis legatura
            if (value.isEmpty()) closeOut(link, failed = false) else events.trySend(RadioEvent.Frame(link.id, value))
        }
    }

    // --- peripheral: GATT server ---

    private fun openServer(): Boolean {
        val server = runCatching { manager?.openGattServer(context, serverCallback) }.getOrNull() ?: return false
        val ch = BluetoothGattCharacteristic(
            BleConstants.CHARACTERISTIC,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        ch.addDescriptor(
            BluetoothGattDescriptor(
                BleConstants.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        val service = BluetoothGattService(BleConstants.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(ch)
        gattServer = server
        characteristic = ch
        return server.addService(service)
    }

    private suspend fun notify(link: InLink, frame: ByteArray): Boolean = notifyMutex.withLock {
        val server = gattServer
        val ch = characteristic
        val address = link.device.address
        if (server == null || ch == null || incoming[address] !== link) return false
        repeat(5) {
            val done = CompletableDeferred<Boolean>()
            notifySignal = done
            notifyAddress = address
            val accepted = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(link.device, ch, false, frame) == BluetoothStatusCodes.SUCCESS
                } else {
                    // valoarea e comuna tuturor clientilor, de aceea notificarile sunt serializate global
                    @Suppress("DEPRECATION")
                    ch.value = frame
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(link.device, ch, false)
                }
            }.getOrDefault(false)
            if (accepted) {
                val ok = withTimeoutOrNull(BleConstants.WRITE_TIMEOUT_MS) { done.await() } ?: false
                notifySignal = null
                return ok
            }
            notifySignal = null
            if (incoming[address] !== link) return false
            delay(25)
        }
        false
    }

    private fun closeIn(link: InLink) {
        val address = link.device.address
        if (incoming.remove(address) == null) return
        val server = gattServer
        val ch = characteristic
        if (server != null && ch != null) {
            // clientul trateaza notificarea goala ca "inchide"; cancelConnection singur nu rupe mereu legatura
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(link.device, ch, false, ByteArray(0))
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = ByteArray(0)
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(link.device, ch, false)
                }
            }
            scope.launch {
                delay(300)
                runCatching { server.cancelConnection(link.device) }
            }
        }
        events.trySend(RadioEvent.LinkDown(link.id))
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = post {
            serviceReady = status == BluetoothGatt.GATT_SUCCESS
            if (serviceReady && active) requestAdvertise() else log("serviciul GATT nu a putut fi adaugat: $status")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = post {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverMtu.remove(device.address)
                if (notifyAddress == device.address) notifySignal?.complete(false)
                val link = incoming.remove(device.address) ?: return@post
                events.trySend(RadioEvent.LinkDown(link.id))
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = post {
            serverMtu[device.address] = mtu
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) = post {
            val valid = descriptor.uuid == BleConstants.CCCD && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                val status = if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                runCatching { gattServer?.sendResponse(device, requestId, status, 0, null) }
            }
            if (!valid) return@post
            val address = device.address
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                // serverul vede si conexiunile initiate de noi; legatura apare doar cand celalalt se aboneaza
                if (incoming.containsKey(address) || outgoing.containsKey(address)) return@post
                val mtu = serverMtu[address] ?: BleConstants.DEFAULT_MTU
                val link = InLink(nextLinkId++, device, mtu)
                incoming[address] = link
                // fara connect() din partea serverului, cancelConnection() nu poate inchide legatura mai tarziu
                runCatching { gattServer?.connect(device, false) }
                events.trySend(RadioEvent.LinkUp(link.id, address, false, mtu - BleConstants.ATT_OVERHEAD))
            } else {
                val link = incoming.remove(address) ?: return@post
                events.trySend(RadioEvent.LinkDown(link.id))
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) = post {
            val value = if (incoming.containsKey(device.address)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            runCatching { gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value) }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val frame = value?.clone()
            post {
                val valid = ch.uuid == BleConstants.CHARACTERISTIC && !preparedWrite && offset == 0 && frame != null
                if (responseNeeded) {
                    val status = if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                    runCatching { gattServer?.sendResponse(device, requestId, status, 0, null) }
                }
                if (!valid) return@post
                val link = incoming[device.address] ?: return@post
                events.trySend(RadioEvent.Frame(link.id, frame))
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = post {
            if (notifyAddress == device.address) notifySignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // --- scanare ---

    private fun requestScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            val wait = maxOf(lastScanStartAt + BleConstants.SCAN_START_SPACING_MS, scanBlockedUntil) - now()
            if (wait > 0) delay(wait)
            if (!active) return@launch
            stopScanNow()
            startScanNow()
            publishStatus()
        }
    }

    private fun startScanNow() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        // filtrul pe UUID e obligatoriu ca scanarea sa mearga cu ecranul stins
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(
                when (scanMode) {
                    PowerMode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                    PowerMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                    PowerMode.LOW_LATENCY -> ScanSettings.SCAN_MODE_LOW_LATENCY
                }
            )
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0)
            .build()
        val started = runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }.isSuccess
        lastScanStartAt = now()
        if (started) {
            scanning = true
            scanStartedAt = lastScanStartAt
            log("scanare pornita ($scanMode)")
        }
    }

    private fun stopScanNow() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = post { onScan(result) }

        override fun onBatchScanResults(results: MutableList<ScanResult>) = post { results.forEach(::onScan) }

        override fun onScanFailed(errorCode: Int) = post {
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return@post
            scanning = false
            // 6 = SCAN_FAILED_SCANNING_TOO_FREQUENTLY
            scanBlockedUntil = now() + if (errorCode == 6) 30_000L else 10_000L
            log("scanare esuata: $errorCode")
            publishStatus()
        }
    }

    private fun onScan(result: ScanResult) {
        if (!active) return
        val now = now()
        lastScanResultAt = now
        val address = result.device.address
        scanned[address] = result.device
        val data = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.SERVICE))
        val known = data != null && data.size >= BleConstants.ADVERT_DATA_SIZE &&
            (data[0].toInt() and 0xff) == BleConstants.ADVERT_VERSION
        val peerFlags = if (known) data[1].toInt() and 0xff else NodeFlags.ACCEPTS_CONNECTIONS
        val peerPrefix = if (known) ByteBuffer.wrap(data, 2, 4).int else null
        if (peerPrefix == prefix) return
        val last = lastReported[address]
        if (last != null && now - last < 1_000) return
        lastReported[address] = now
        events.trySend(RadioEvent.PeerSeen(address, peerPrefix, peerFlags, result.rssi))
    }

    // --- advertising ---

    private fun requestAdvertise() {
        advertiseJob?.cancel()
        advertiseJob = scope.launch {
            val wait = maxOf(advertiseBlockedUntil - now(), 300L)
            delay(wait)
            if (!active || !canAdvertise) return@launch
            stopAdvertisingNow()
            startAdvertisingNow()
        }
    }

    private fun startAdvertisingNow() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(
                when (advertiseMode) {
                    PowerMode.LOW_POWER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
                    PowerMode.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
                    PowerMode.LOW_LATENCY -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                }
            )
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val uuid = ParcelUuid(BleConstants.SERVICE)
        // UUID-ul sta in advertising ca filtrele de scanare sa-l prinda; datele scurte merg in scan response
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(uuid).build()
        val payload = ByteBuffer.allocate(BleConstants.ADVERT_DATA_SIZE)
            .put(BleConstants.ADVERT_VERSION.toByte()).put(flags.toByte()).putInt(prefix).array()
        val response = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceData(uuid, payload).build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = post {
                if (advertiseCallback !== this) return@post
                advertising = true
                publishStatus()
            }

            override fun onStartFailure(errorCode: Int) = post {
                if (advertiseCallback !== this) return@post
                when (errorCode) {
                    ADVERTISE_FAILED_ALREADY_STARTED -> advertising = true
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> {
                        advertising = false
                        canAdvertise = false
                    }
                    else -> {
                        advertising = false
                        advertiseBlockedUntil = now() + 15_000L
                    }
                }
                log("advertising esuat: $errorCode")
                publishStatus()
            }
        }
        advertiseCallback = callback
        runCatching { advertiser.startAdvertising(settings, data, response, callback) }
            .onFailure { log("advertising: ${it.message}") }
    }

    private fun stopAdvertisingNow() {
        val callback = advertiseCallback ?: return
        advertiseCallback = null
        advertising = false
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
    }
}
