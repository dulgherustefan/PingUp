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
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
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
        val ops = GattOps<Op>()
        var pauseUntil = 0L
        var setupJob: Job? = null
        val mutex = Mutex()

        /** Cadre sosite cat timp legatura inca se configureaza; se predau imediat dupa LinkUp. */
        val early = ArrayList<ByteArray>()
    }

    private class InLink(val id: Int, val device: BluetoothDevice, val mtu: Int) {
        var pauseUntil = 0L
    }

    private enum class AdvOp { START, ENABLE, DISABLE, PARAMETERS, DATA }

    /** Un set de advertising: cel legacy pe 1M, sau cel extins pe Coded PHY pentru raza lunga. */
    private inner class AdvSet(val coded: Boolean) {
        var set: AdvertisingSet? = null
        var callback: AdvertisingSetCallback? = null
        var startedAt = 0L
        var appliedFlags = 0
        var appliedInterval = 0
        private var op: AdvOp? = null
        private var signal: CompletableDeferred<Int>? = null

        val name get() = if (coded) "coded" else "1M"

        suspend fun await(kind: AdvOp, start: () -> Unit): Int {
            val done = CompletableDeferred<Int>()
            op = kind
            signal = done
            val status = runCatching(start).fold(
                onSuccess = { withTimeoutOrNull(BleConstants.OPERATION_TIMEOUT_MS) { done.await() } ?: ADV_TIMEOUT },
                onFailure = {
                    log("advertising $name: ${it.message}")
                    AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR
                },
            )
            if (signal === done) {
                signal = null
                op = null
            }
            return status
        }

        fun finish(kind: AdvOp, status: Int) {
            if (op == kind) signal?.complete(status)
        }

        fun stop() {
            val cb = callback ?: return
            callback = null
            set = null
            signal?.complete(AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertisingSet(cb) }
        }

        fun newCallback() = object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) = post {
                if (callback === this) started(advertisingSet, status)
            }

            override fun onAdvertisingEnabled(advertisingSet: AdvertisingSet?, enable: Boolean, status: Int) = post {
                if (callback === this) finish(if (enable) AdvOp.ENABLE else AdvOp.DISABLE, status)
            }

            override fun onAdvertisingParametersUpdated(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) = post {
                if (callback === this) finish(AdvOp.PARAMETERS, status)
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet?, status: Int) = post {
                if (callback === this) finish(AdvOp.DATA, status)
            }

            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet?, status: Int) = post {
                if (callback === this) finish(AdvOp.DATA, status)
            }
        }

        fun started(advertisingSet: AdvertisingSet?, status: Int) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS && advertisingSet != null) {
                set = advertisingSet
                log("advertising $name pornit")
                if (!coded) {
                    if (failures.demoted) log("advertising merge din nou, telefonul nu mai e frunza")
                    failures.reset()
                }
                publishStatus()
                // flag-urile sau modul s-au putut schimba cat a durat pornirea
                requestAdvertise()
            } else {
                callback = null
                set = null
                if (coded) codedFailed(status) else legacyFailed(status)
            }
            finish(AdvOp.START, status)
        }
    }

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
    private val sightings = Sightings()
    private var scanStatsAt = 0L
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

    private var canAdvertise = true
    private val failures = AdvertiseFailures()
    private val legacyAdv = AdvSet(coded = false)
    private val codedAdv = AdvSet(coded = true)
    private var advertiseJob: Job? = null
    private var advertisePending = false
    private var advertiseBlockedUntil = 0L
    private var advertiseInPlace = true
    private var codedDisabled = false
    private var codedBlockedUntil = 0L

    private var codedPhy = false
    private var extendedAdvertising = false
    private var maxAdvertisingDataLength = 0
    private var multipleAdvertisement = false
    private var longRange = false

    private var lastStatus: RadioStatus? = null
    private var waitingReason: String? = null

    private companion object {
        const val MAX_EARLY_FRAMES = 16
        const val ADV_TIMEOUT = -1
    }

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
        // isMultipleAdvertisementSupported cere cel putin 5 instante; noua ne ajunge una, iar primul start decide
        canAdvertise = adapter.bluetoothLeAdvertiser != null
        failures.reset()
        advertiseInPlace = true
        codedDisabled = false
        codedBlockedUntil = 0L
        codedPhy = adapter.isLeCodedPhySupported
        extendedAdvertising = adapter.isLeExtendedAdvertisingSupported
        maxAdvertisingDataLength = adapter.leMaximumAdvertisingDataLength
        multipleAdvertisement = adapter.isMultipleAdvertisementSupported
        longRange = BleConstants.LONG_RANGE && codedPhy && extendedAdvertising
        log(
            "radio pornit, advertising ${if (canAdvertise) "suportat" else "NESUPORTAT (telefon frunza)"}, " +
                "coded=$codedPhy extins=$extendedAdvertising maxAdv=$maxAdvertisingDataLength " +
                "multi=$multipleAdvertisement razaLunga=$longRange"
        )
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
        legacyAdv.stop()
        codedAdv.stop()
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
        sightings.clear()
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
        val missingSet = legacyAdv.set == null || codedAdv.wanted() && codedAdv.set == null
        if (legacyAdv.wanted() && missingSet && advertiseJob?.isActive != true) requestAdvertise()
        // adresele MAC se rotesc; ce nu a mai fost vazut de un minut nu mai e de folos
        for (address in sightings.forget(now)) {
            if (!outgoing.containsKey(address)) scanned.remove(address)
        }
        if (now - scanStatsAt >= 60_000) {
            scanStatsAt = now
            val (results, withoutResponse) = sightings.drainStats()
            if (results > 0) log("scanare: $withoutResponse din $results rezultate fara scan response")
        }
        publishStatus()
    }

    private fun publishStatus() {
        val status = RadioStatus(
            active, scanning, legacyAdv.set != null, canAdvertise && !failures.demoted,
            codedPhy, extendedAdvertising, maxAdvertisingDataLength, multipleAdvertisement, codedAdv.set != null,
        )
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
        // Doar dispozitive venite dintr-o scanare: getRemoteDevice() pe o adresa aleatoare nu stie tipul
        // adresei, iar conectarea ramane agatata pana la timeout.
        val device = scanned[address]
        if (device == null) {
            events.trySend(RadioEvent.ConnectFailed(address))
            return
        }
        val link = OutLink(nextLinkId++, address)
        outgoing[address] = link
        link.setupJob = scope.launch {
            val ok = withTimeoutOrNull(BleConstants.SETUP_TIMEOUT_MS) { setUp(link, device) } ?: false
            if (ok && !link.closed && !link.ops.dropped) {
                link.ready = true
                events.trySend(RadioEvent.LinkUp(link.id, address, true, link.mtu - BleConstants.ATT_OVERHEAD))
                // fara punct de suspendare intre LinkUp si cadrele timpurii, ca ordinea sa ramana cea de pe fir
                for (frame in link.early) events.trySend(RadioEvent.Frame(link.id, frame))
                link.early.clear()
                // PHY-ul ales arata in log daca legatura a mers pe Coded
                if (longRange) runCatching { link.gatt?.readPhy() }
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

    private suspend fun setUp(link: OutLink, device: BluetoothDevice): Boolean {
        val callback = ClientCallback(link)
        val startedAt = now()
        if (!link.ops.run(Op.CONNECT, BleConstants.CONNECT_TIMEOUT_MS) {
                // varianta noua cu BluetoothGattConnectionSettings exista doar pe versiunile recente de Android
                @Suppress("DEPRECATION")
                link.gatt = if (longRange) {
                    device.connectGatt(
                        context, false, callback, BluetoothDevice.TRANSPORT_LE,
                        BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK,
                    )
                } else {
                    device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                }
                link.gatt != null
            }) return false
        log("conectat ${link.address.takeLast(5)} in ${now() - startedAt} ms")
        val gatt = link.gatt ?: return false
        // unele stive pierd prima operatie daca vine imediat dupa conectare
        delay(150)
        if (link.ops.dropped) return false
        if (!link.ops.run(Op.MTU, BleConstants.OPERATION_TIMEOUT_MS) { gatt.requestMtu(BleConstants.REQUESTED_MTU) }) {
            log("MTU nenegociat cu ${link.address.takeLast(5)}")
            return false
        }
        if (!link.ops.run(Op.DISCOVER, BleConstants.OPERATION_TIMEOUT_MS) { gatt.discoverServices() }) {
            log("descoperirea serviciilor a esuat la ${link.address.takeLast(5)}")
            return false
        }
        val ch = gatt.getService(BleConstants.SERVICE)?.getCharacteristic(BleConstants.CHARACTERISTIC)
        val cccd = ch?.getDescriptor(BleConstants.CCCD)
        if (ch == null || cccd == null) {
            log("serviciul lipseste la ${link.address.takeLast(5)}")
            return false
        }
        link.characteristic = ch
        if (!gatt.setCharacteristicNotification(ch, true)) {
            log("notificarile nu au putut fi activate local pentru ${link.address.takeLast(5)}")
            return false
        }
        var accepted = false
        val subscribed = link.ops.run(Op.DESCRIPTOR, BleConstants.OPERATION_TIMEOUT_MS) {
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            accepted
        }
        if (!subscribed) {
            // Cele doua cazuri cer diagnostic diferit: stiva a refuzat scrierea, sau a trimis-o si confirmarea nu a venit.
            log(
                if (accepted) "abonarea trimisa, dar neconfirmata in ${BleConstants.OPERATION_TIMEOUT_MS / 1000}s de ${link.address.takeLast(5)}"
                else "stiva a refuzat scrierea de abonare catre ${link.address.takeLast(5)}"
            )
        }
        return subscribed
    }

    private suspend fun write(link: OutLink, frame: ByteArray): Boolean = link.mutex.withLock {
        val pause = link.pauseUntil - now()
        if (pause > 0) delay(pause)
        val gatt = link.gatt
        val ch = link.characteristic
        if (!link.ready || link.closed || gatt == null || ch == null) return false
        // stiva refuza o scriere cat timp precedenta e inca in zbor; reincercam scurt
        repeat(5) {
            var accepted = false
            val ok = link.ops.run(Op.WRITE, BleConstants.WRITE_TIMEOUT_MS) {
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
        link.ops.drop()
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
                link.ops.finish(Op.CONNECT, true)
            } else {
                if (!link.closed) log("deconectat ${link.address.takeLast(5)} status=$status")
                if (link.ready) closeOut(link, failed = false) else link.ops.drop()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = post {
            if (status == BluetoothGatt.GATT_SUCCESS) link.mtu = mtu
            link.ops.finish(Op.MTU, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = post {
            link.ops.finish(Op.DISCOVER, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = post {
            link.ops.finish(Op.DESCRIPTOR, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) = post {
            if (status == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                link.pauseUntil = now() + BleConstants.CONGESTION_PAUSE_MS
                log("congestie (143) la ${link.address.takeLast(5)}")
            }
            link.ops.finish(Op.WRITE, sendAccepted(status))
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = post {
            if (status == BluetoothGatt.GATT_SUCCESS) log("phy ${link.address.takeLast(5)} tx=$txPhy rx=$rxPhy")
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) = post {
            if (status == BluetoothGatt.GATT_SUCCESS) log("phy schimbat ${link.address.takeLast(5)} tx=$txPhy rx=$rxPhy")
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
            if (link.closed) return@post
            if (!link.ready) {
                // Serverul trimite HELLO imediat ce ne abonam, adesea inainte ca abonarea sa fie confirmata
                // la noi. Aruncat aici, HELLO-ul s-ar pierde si legatura ar muri dupa timeout.
                if (value.isEmpty()) closeOut(link, failed = true)
                else if (link.early.size < MAX_EARLY_FRAMES) link.early += value
                return@post
            }
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

    private suspend fun notify(link: InLink, frame: ByteArray): Boolean {
        // pauza dupa congestie se asteapta in afara mutex-ului, ca sa nu tina pe loc ceilalti clienti
        val pause = link.pauseUntil - now()
        if (pause > 0) delay(pause)
        return notifyLocked(link, frame)
    }

    private suspend fun notifyLocked(link: InLink, frame: ByteArray): Boolean = notifyMutex.withLock {
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
            if (status == BluetoothGatt.GATT_CONNECTION_CONGESTED) {
                incoming[device.address]?.pauseUntil = now() + BleConstants.CONGESTION_PAUSE_MS
                log("congestie (143) la ${device.address.takeLast(5)}")
            }
            if (notifyAddress == device.address) notifySignal?.complete(sendAccepted(status))
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
            // pe 1M si pe Coded; advertising-urile legacy se primesc in continuare
            .apply { if (longRange) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }
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
        val data = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.SERVICE))
        val known = data != null && data.size >= BleConstants.ADVERT_DATA_SIZE &&
            (data[0].toInt() and 0xff) == BleConstants.ADVERT_VERSION
        val peerFlags = if (known) data[1].toInt() and 0xff else NodeFlags.ACCEPTS_CONNECTIONS
        val peerPrefix = if (known) ByteBuffer.wrap(data, 2, 4).int else null
        if (peerPrefix == prefix) return
        scanned[address] = result.device
        val coded = result.primaryPhy == BluetoothDevice.PHY_LE_CODED
        val rssi = sightings.heard(address, peerPrefix, coded, result.rssi, now) ?: return
        events.trySend(RadioEvent.PeerSeen(address, peerPrefix, peerFlags, rssi))
    }

    // --- advertising ---
    //
    // Fiecare set se porneste o data si apoi se actualizeaza pe loc. Un set nou primeste alta adresa MAC,
    // iar peer-ii care tocmai se conectau la cea veche raman agatati pana la timeout.

    private fun AdvSet.wanted(): Boolean {
        val legacyWanted = active && canAdvertise && serviceReady
        if (!coded) return legacyWanted
        // setul coded vine doar dupa cel legacy, ca sa nu-i ia instanta
        return legacyWanted && longRange && !codedDisabled && legacyAdv.set != null && now() >= codedBlockedUntil
    }

    private fun requestAdvertise() {
        advertisePending = true
        if (advertiseJob?.isActive == true) return
        // job-ul nu se anuleaza din afara: o schimbare de parametri nu trebuie taiata la jumatate
        advertiseJob = scope.launch {
            while (advertisePending) {
                advertisePending = false
                delay(maxOf(advertiseBlockedUntil - now(), BleConstants.ADVERTISE_DEBOUNCE_MS))
                if (legacyAdv.wanted()) legacyAdv.sync()
                if (codedAdv.wanted()) codedAdv.sync()
            }
        }
    }

    /** Aduce setul la flag-urile si modul curente; ce nu merge pe loc se face prin oprire si pornire. */
    private suspend fun AdvSet.sync() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val current = set
        if (current == null) {
            if (callback == null) return start(advertiser)
            // pornirea e inca in curs; un al doilea set ar ocupa inca o instanta
            if (now() - startedAt < BleConstants.ADVERTISE_START_GIVE_UP_MS) return
            stop()
            started(null, AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR)
            return
        }
        val wantedFlags = flags
        if (appliedFlags != wantedFlags && !updateInPlace { setData(current, wantedFlags) }) return restart(advertiser)
        if (set !== current) return
        val wantedInterval = interval()
        if (appliedInterval != wantedInterval && !updateInPlace { setInterval(current, wantedInterval) }) return restart(advertiser)
    }

    private suspend fun AdvSet.updateInPlace(update: suspend () -> Boolean): Boolean {
        if (!advertiseInPlace) return false
        if (update()) return true
        if (callback != null) {
            advertiseInPlace = false
            log("advertising $name: actualizarea pe loc a esuat, de acum repornim setul")
        }
        return false
    }

    private suspend fun AdvSet.setData(current: AdvertisingSet, wantedFlags: Int): Boolean {
        val status = await(AdvOp.DATA) {
            if (coded) current.setAdvertisingData(codedData(wantedFlags)) else current.setScanResponseData(serviceData(wantedFlags))
        }
        if (status != AdvertisingSetCallback.ADVERTISE_SUCCESS) return false
        appliedFlags = wantedFlags
        return true
    }

    private suspend fun AdvSet.setInterval(current: AdvertisingSet, interval: Int): Boolean {
        val success = AdvertisingSetCallback.ADVERTISE_SUCCESS
        // parametrii se schimba doar cu setul oprit; fiecare pas asteapta confirmarea stivei
        if (await(AdvOp.DISABLE) { current.enableAdvertising(false, 0, 0) } != success) return false
        if (await(AdvOp.PARAMETERS) { current.setAdvertisingParameters(parameters(interval)) } != success) return false
        if (await(AdvOp.ENABLE) { current.enableAdvertising(true, 0, 0) } != success) return false
        appliedInterval = interval
        return true
    }

    private suspend fun AdvSet.restart(advertiser: BluetoothLeAdvertiser) {
        stop()
        if (!wanted()) return
        start(advertiser)
    }

    private suspend fun AdvSet.start(advertiser: BluetoothLeAdvertiser) {
        val cb = newCallback()
        callback = cb
        startedAt = now()
        appliedFlags = flags
        appliedInterval = interval()
        val parameters = parameters(appliedInterval)
        val status = await(AdvOp.START) {
            if (coded) advertiser.startAdvertisingSet(parameters, codedData(appliedFlags), null, null, null, cb)
            else advertiser.startAdvertisingSet(parameters, uuidData(), serviceData(appliedFlags), null, null, cb)
        }
        // o exceptie la pornire nu aduce niciun callback
        if (status == AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR && callback === cb && set == null) {
            stop()
            started(null, status)
        }
    }

    private fun legacyFailed(status: Int) {
        when {
            status == AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> canAdvertise = false
            status == AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS && codedAdv.callback != null -> {
                // setul coded nu are voie sa ia locul celui legacy; renuntam la el pana la repornirea radioului
                codedAdv.stop()
                codedDisabled = true
                log("setul coded oprit ca sa elibereze o instanta")
                requestAdvertise()
            }
            else -> advertiseBlockedUntil = now() + failures.failed(status)
        }
        log("advertising esuat: $status")
        if (failures.count == BleConstants.ADVERTISE_DEMOTE_AFTER) log("advertising pica repetat, telefonul se poarta ca frunza")
        publishStatus()
    }

    private fun codedFailed(status: Int) {
        val permanent = status == AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ||
            status == AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE
        if (permanent) codedDisabled = true else codedBlockedUntil = now() + BleConstants.CODED_RETRY_MS
        log("advertising coded esuat: $status")
        publishStatus()
    }

    private fun AdvSet.interval(): Int = when (advertiseMode) {
        PowerMode.LOW_POWER -> AdvertisingSetParameters.INTERVAL_HIGH
        PowerMode.BALANCED -> AdvertisingSetParameters.INTERVAL_MEDIUM
        // un pachet pe Coded tine de ~8 ori mai mult in aer; nu coboram sub 250 ms
        PowerMode.LOW_LATENCY -> if (coded) AdvertisingSetParameters.INTERVAL_MEDIUM else AdvertisingSetParameters.INTERVAL_LOW
    }

    private fun AdvSet.parameters(interval: Int): AdvertisingSetParameters {
        val builder = AdvertisingSetParameters.Builder()
            .setConnectable(true)
            .setInterval(interval)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
        if (coded) {
            builder.setLegacyMode(false).setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED).setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
        } else {
            builder.setLegacyMode(true).setScannable(true)
        }
        return builder.build()
    }

    private fun payload(flags: Int): ByteArray = ByteBuffer.allocate(BleConstants.ADVERT_DATA_SIZE)
        .put(BleConstants.ADVERT_VERSION.toByte()).put(flags.toByte()).putInt(prefix).array()

    // UUID-ul sta in advertising ca filtrele de scanare sa-l prinda; datele scurte merg in scan response
    private fun uuidData(): AdvertiseData = AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(BleConstants.SERVICE)).build()

    private fun serviceData(flags: Int): AdvertiseData = AdvertiseData.Builder().setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false).addServiceData(ParcelUuid(BleConstants.SERVICE), payload(flags)).build()

    // setul coded nu are scan response: UUID-ul pentru filtre si datele intra amandoua in advertising
    private fun codedData(flags: Int): AdvertiseData {
        val uuid = ParcelUuid(BleConstants.SERVICE)
        return AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
            .addServiceUuid(uuid).addServiceData(uuid, payload(flags)).build()
    }
}
