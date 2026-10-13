package ro.safetyplease.ui.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAdvertisementDataServiceDataKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCentralManagerScanOptionAllowDuplicatesKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBPeripheralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import ro.safetyplease.core.ble.AdvertData
import ro.safetyplease.core.ble.BleLayout
import ro.safetyplease.core.ble.ScanCycle
import ro.safetyplease.core.ble.Sightings
import ro.safetyplease.core.mesh.PowerMode
import ro.safetyplease.core.mesh.PowerPlan
import ro.safetyplease.core.mesh.RadioEvent
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.protocol.NodeFlags
import ro.safetyplease.core.protocol.PacketCodec
import ro.safetyplease.core.session.MeshRadio
import kotlin.coroutines.resume

/**
 * The mesh over CoreBluetooth, on the layout every Ping Up phone keeps ([BleLayout]): a GATT server other phones
 * connect to, and a central that connects to the phones it finds.
 *
 * iOS doesn't let an app advertise service data, so no phone can read this one's prefix or flags from its advertising.
 * It says it can't advertise, and the engine has it start every link it wants; Android phones leave such a phone to
 * connect, and between two iPhones the duplicate rule closes one of the two links. An iPhone can't drop a central that
 * connected to it either: it sends the empty notification that tells the central to go (PROTOCOL.md, section 2).
 *
 * Out of the front ([inFront] false) iOS runs the app only while it hands it a Bluetooth event, so no timer can be
 * counted on there: the scan doesn't pause, and the events themselves renew it ([renewScan]).
 *
 * Everything that touches CoreBluetooth runs on the process's one queue, one thing at a time, with the process's two
 * managers ([Managers]); the engine gets [events] in order.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosRadio(
    private val scope: CoroutineScope,
    private val inFront: StateFlow<Boolean>,
    private val log: (String) -> Unit,
) : MeshRadio {
    private val channel = Channel<RadioEvent>(Channel.UNLIMITED)
    override val events: ReceiveChannel<RadioEvent> = channel

    private val service = CBUUID.UUIDWithString(BleLayout.SERVICE)
    private val characteristicId = CBUUID.UUIDWithString(BleLayout.CHARACTERISTIC)
    private val centralEvents = CentralEvents()
    private val peripheralEvents = PeripheralEvents()
    private val linkEvents = LinkEvents()
    private var central: CBCentralManager? = null
    private var server: CBPeripheralManager? = null
    private var characteristic: CBMutableCharacteristic? = null
    private var serviceAdded = false
    private var advertStarting = false

    private var running = false
    private var front = true
    private var flags = NodeFlags.ACCEPTS_CONNECTIONS
    private var plan: PowerPlan? = null
    private val cycle = ScanCycle()
    private var scanning = false
    private var scanStartedAt = 0L
    private var scanRenewedAt = 0L
    private var cycleJob: Job? = null
    private var cycleRound = 0
    private var frontJob: Job? = null
    private var forgetJob: Job? = null
    private val sightings = Sightings()
    private val found = HashMap<String, CBPeripheral>()
    private var lastStatus: RadioStatus? = null
    private var nextLink = 1

    /** A frame waiting for the stack to take it; [done] says whether it went. */
    private class Pending(val frame: ByteArray, val done: CancellableContinuation<Boolean>)

    /** A link we opened, to a phone's GATT server: we write, it notifies. */
    private inner class OutLink(val id: Int, val peripheral: CBPeripheral) {
        val address: String = peripheral.identifier.UUIDString
        var characteristic: CBCharacteristic? = null
        var ready = false
        var closed = false
        val early = ArrayList<ByteArray>()
        val waiting = ArrayDeque<Pending>()
        var timeout: Job? = null
    }

    /** A link a phone opened to our server: it writes, we notify. */
    private class InLink(val id: Int, val central: CBCentral) {
        val waiting = ArrayDeque<Pending>()
    }

    private val outgoing = HashMap<String, OutLink>()
    private val incoming = HashMap<String, InLink>()

    /** Centrals told to go whose empty notification didn't fit in the stack's queue yet. */
    private val farewells = ArrayList<CBCentral>()

    /** Links iOS kept while it had ended the app; the engine starts without them, so they close once Bluetooth is on. */
    private val restored = ArrayList<CBPeripheral>()

    private fun onQueue(block: () -> Unit) = dispatch_async(Managers.queue, block)

    // --- MeshRadio ---

    /** The first radio makes the managers, which is what makes iOS ask for Bluetooth the first time. */
    override fun start() = onQueue {
        if (running) return@onQueue
        running = true
        front = inFront.value
        sightings.start()
        val madeBefore = Managers.central != null
        val central = Managers.central ?: CBCentralManager(
            centralEvents, Managers.queue,
            mapOf<Any?, Any?>(CBCentralManagerOptionShowPowerAlertKey to false, CBCentralManagerOptionRestoreIdentifierKey to CENTRAL_ID),
        ).also { Managers.central = it }
        val server = Managers.server ?: CBPeripheralManager(
            peripheralEvents, Managers.queue,
            mapOf<Any?, Any?>(CBPeripheralManagerOptionShowPowerAlertKey to false, CBPeripheralManagerOptionRestoreIdentifierKey to SERVER_ID),
        ).also { Managers.server = it }
        this.central = central
        this.server = server
        central.delegate = centralEvents
        server.delegate = peripheralEvents
        if (madeBefore) {
            // an earlier radio's managers have nothing new to say: this one starts from the state they're in
            centralEvents.centralManagerDidUpdateState(central)
            peripheralEvents.peripheralManagerDidUpdateState(server)
        }
        frontJob = scope.launch { inFront.collect { front -> onQueue { frontChanged(front) } } }
        forgetJob = scope.launch {
            while (true) {
                delay(FORGET_EVERY_MS)
                onQueue { sightings.forget(IosClock.monoMs()).forEach(found::remove) }
            }
        }
    }

    override fun stop() = onQueue {
        if (!running) return@onQueue
        running = false
        frontJob?.cancel()
        forgetJob?.cancel()
        cancelCycle()
        tearDown()
        // the managers stay for the next radio; until then, what they report concerns nobody
        central?.delegate = null
        server?.delegate = null
        central = null
        server = null
        publishStatus()
    }

    // iOS asks once and then only Settings can change the answer; Bluetooth comes back by itself, with its state
    override fun retry() = onQueue { publishStatus() }

    override fun setAdvertising(flags: Int, prefix: Int) = onQueue {
        // the prefix can't be advertised; whether to take connections at all can
        if (this.flags == flags) return@onQueue
        this.flags = flags
        advertise()
    }

    // iOS chooses the scan and advertising intervals itself
    override fun setPowerMode(scan: PowerMode, advertise: PowerMode) = Unit

    /** The duty cycle of [ro.safetyplease.core.mesh.PowerPolicy], as on Android: the scan pauses for the off part. */
    override fun setPower(plan: PowerPlan) = onQueue {
        if (plan == this.plan) return@onQueue
        this.plan = plan
        log("power: ${plan.profile} duty=${plan.duty}")
        if (!cycle.set(plan.duty)) return@onQueue
        // the old duty's timer would otherwise end the new duty's first window early
        cancelCycle()
        // a scan already running keeps its start time; a paused one starts again under the new duty
        if (scanning) scheduleCycle() else startScan()
    }

    override fun connect(address: String, timeoutMs: Long) = onQueue {
        val central = central
        val peripheral = found[address]
        if (!running || central == null || peripheral == null || outgoing.containsKey(address) || !bluetoothOn()) {
            channel.trySend(RadioEvent.ConnectFailed(address))
            return@onQueue
        }
        val link = OutLink(nextLink++, peripheral)
        outgoing[address] = link
        peripheral.delegate = linkEvents
        central.connectPeripheral(peripheral, null)
        link.timeout = scope.launch {
            delay(timeoutMs)
            onQueue { if (!link.ready && !link.closed) closeOut(link) }
        }
    }

    override fun disconnect(link: Int) = onQueue {
        outgoing.values.firstOrNull { it.id == link }?.let { return@onQueue closeOut(it) }
        incoming.values.firstOrNull { it.id == link }?.let { closeIn(it) }
    }

    override suspend fun send(link: Int, frame: ByteArray): Boolean = suspendCancellableCoroutine { done ->
        onQueue {
            val pending = Pending(frame, done)
            val out = outgoing.values.firstOrNull { it.id == link }
            val into = incoming.values.firstOrNull { it.id == link }
            when {
                out != null && out.ready && !out.closed -> {
                    out.waiting.addLast(pending)
                    drain(out)
                    if (done.isActive) expire(pending, out.waiting) { drain(out) }
                }
                into != null -> {
                    into.waiting.addLast(pending)
                    drain(into)
                    if (done.isActive) expire(pending, into.waiting) { drain(into) }
                }
                else -> done.resume(false)
            }
        }
    }

    // --- state ---

    private fun bluetoothOn() = central?.state == CBManagerStatePoweredOn

    private fun publishStatus() {
        // canAdvertise false: no phone can learn this one's prefix from its advertising, so it starts its links itself
        val status = RadioStatus(
            bluetoothOn = running && bluetoothOn(), scanning = scanning || cycle.paused, advertising = server?.isAdvertising == true,
            canAdvertise = false,
        )
        if (status == lastStatus) return
        lastStatus = status
        channel.trySend(RadioEvent.Status(status))
    }

    private fun tearDown() {
        stopScan()
        cycle.resume()
        // with Bluetooth off iOS took the advertising and the service down itself, and says so if asked again
        val serverOn = server?.state == CBManagerStatePoweredOn
        if (serverOn) server?.stopAdvertising()
        advertStarting = false
        outgoing.values.toList().forEach(::closeOut)
        incoming.values.toList().forEach(::closeIn)
        farewells.clear()
        if (serverOn) server?.removeAllServices()
        serviceAdded = false
        characteristic = null
        sightings.stop()
        found.clear()
    }

    /** Out of the front a paused scan would never start again, as nothing wakes the app for a timer. */
    private fun frontChanged(front: Boolean) {
        if (front == this.front) return
        this.front = front
        if (!running) return
        if (!front) {
            cancelCycle()
            if (cycle.paused) startScan()
        } else if (scanning) {
            // a scan started again brings back the repeats, and the duty cycle from a new window
            stopScan()
            startScan()
        }
    }

    // --- scanning ---

    private fun scanFor(central: CBCentralManager) {
        // in front every repeat keeps the phones around fresh for the engine; in the background iOS ignores the option
        central.scanForPeripheralsWithServices(listOf(service), mapOf<Any?, Any?>(CBCentralManagerScanOptionAllowDuplicatesKey to true))
        scanRenewedAt = IosClock.monoMs()
    }

    private fun startScan() {
        val central = central ?: return
        if (!running || !bluetoothOn()) return
        scanFor(central)
        scanning = true
        scanStartedAt = IosClock.monoMs()
        cycle.resume()
        scheduleCycle()
        publishStatus()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        // with Bluetooth off the scan has stopped already
        if (bluetoothOn()) central?.stopScan()
    }

    /**
     * In the background iOS reports a phone once per scan and runs the app only while it hands it a Bluetooth event,
     * so the events renew the scan, which brings the phones around back to the engine: right after a link went down
     * ([linkLost]), when another phone is wanted, and otherwise once every [RENEW_MS] at most. Apple counts tighter
     * restarts as abuse, and a suspended app couldn't run a timer for them anyway.
     */
    private fun renewScan(linkLost: Boolean = false) {
        val central = central ?: return
        if (!running || !scanning || front) return
        if (IosClock.monoMs() - scanRenewedAt < if (linkLost) RENEW_AFTER_LOSS_MS else RENEW_MS) return
        central.stopScan()
        scanFor(central)
    }

    private fun cancelCycle() {
        cycleJob?.cancel()
        // a block already on the queue checks the round, or it would still end the next window early
        cycleRound++
    }

    private fun scheduleCycle() {
        cancelCycle()
        // out of the front a pause would never end: the scan stays on there
        if (!front) return
        val at = cycle.nextAt(scanning, scanStartedAt) ?: return
        val round = cycleRound
        cycleJob = scope.launch {
            delay(at - IosClock.monoMs())
            onQueue {
                if (!running || round != cycleRound) return@onQueue
                if (cycle.paused) {
                    startScan()
                } else {
                    stopScan()
                    cycle.pause(IosClock.monoMs())
                    scheduleCycle()
                    publishStatus()
                }
            }
        }
    }

    private fun heard(peripheral: CBPeripheral, advertisement: Map<Any?, *>, rssi: Int) {
        // 127 is iOS saying it has no reading
        if (!running || rssi == 127) return
        val data = (advertisement[CBAdvertisementDataServiceDataKey] as? Map<*, *>)?.get(service) as? NSData
        val fields = AdvertData.decode(data?.toBytes())
        val address = peripheral.identifier.UUIDString
        val averaged = sightings.heard(address, fields.prefix, coded = false, rssi = rssi, now = IosClock.monoMs()) ?: return
        found[address] = peripheral
        channel.trySend(RadioEvent.PeerSeen(address, fields.prefix, fields.flags, averaged))
    }

    /** iOS invalidates the phones it found: they're looked for anew once Bluetooth is back. */
    private fun forgetPeers() {
        stopScan()
        cancelCycle()
        cycle.resume()
        found.clear()
        sightings.stop()
        sightings.start()
    }

    // --- advertising and the server ---

    private fun openServer() {
        val server = server ?: return
        if (serviceAdded || server.state != CBManagerStatePoweredOn) return
        val ch = CBMutableCharacteristic(
            characteristicId, CBCharacteristicPropertyWriteWithoutResponse or CBCharacteristicPropertyNotify, null, CBAttributePermissionsWriteable,
        )
        val primary = CBMutableService(service, true)
        primary.setCharacteristics(listOf(ch))
        characteristic = ch
        // iOS may still publish the copy from before it ended the app, or an earlier radio's: centrals on it would never hear us
        server.removeAllServices()
        server.addService(primary)
    }

    private fun advertise() {
        val server = server ?: return
        if (!serviceAdded || !running || server.state != CBManagerStatePoweredOn) return
        val accepting = flags and NodeFlags.ACCEPTS_CONNECTIONS != 0
        // isAdvertising stays false until a start completes: the flags are looked at again then
        if (accepting && !server.isAdvertising && !advertStarting) {
            advertStarting = true
            server.startAdvertising(mapOf<Any?, Any?>(CBAdvertisementDataServiceUUIDsKey to listOf(service)))
        } else if (!accepting && server.isAdvertising) {
            server.stopAdvertising()
        }
        publishStatus()
    }

    // --- links ---

    private fun closeOut(link: OutLink) {
        if (link.closed) return
        link.closed = true
        link.timeout?.cancel()
        outgoing.remove(link.address)
        while (link.waiting.isNotEmpty()) link.waiting.removeFirst().done.resume(false)
        // with Bluetooth off the connection is gone already
        if (bluetoothOn()) central?.cancelPeripheralConnection(link.peripheral)
        channel.trySend(if (link.ready) RadioEvent.LinkDown(link.id) else RadioEvent.ConnectFailed(link.address))
    }

    private fun closeIn(link: InLink) {
        val address = link.central.identifier.UUIDString
        if (incoming.remove(address) == null) return
        while (link.waiting.isNotEmpty()) link.waiting.removeFirst().done.resume(false)
        sayGoodbye(link.central)
        channel.trySend(RadioEvent.LinkDown(link.id))
    }

    /** An iPhone can't drop a central: the empty notification tells it to go, once the stack has room for it. */
    private fun sayGoodbye(central: CBCentral) {
        val ch = characteristic ?: return
        val sent = server?.updateValue(ByteArray(0).data(), ch, listOf(central)) == true
        if (!sent && farewells.none { it.identifier == central.identifier }) farewells += central
    }

    /**
     * As on Android, a frame the stack doesn't take within [WRITE_TIMEOUT_MS] fails, and the engine closes the link. It
     * gets one more try first: out of the front the timer can fire on waking, before the stack's own call that it has room.
     */
    private fun expire(pending: Pending, waiting: ArrayDeque<Pending>, retry: () -> Unit) {
        scope.launch {
            delay(WRITE_TIMEOUT_MS)
            onQueue {
                if (!pending.done.isActive) return@onQueue
                retry()
                if (pending.done.isActive && waiting.remove(pending)) pending.done.resume(false)
            }
        }
    }

    /** Writes what waits as long as the stack takes it; the rest goes once it says it's ready. */
    private fun drain(link: OutLink) {
        val ch = link.characteristic ?: return
        while (link.waiting.isNotEmpty() && link.peripheral.canSendWriteWithoutResponse) {
            val next = link.waiting.removeFirst()
            // a sender that stopped waiting doesn't want the frame any more
            if (!next.done.isActive) continue
            link.peripheral.writeValue(next.frame.data(), ch, CBCharacteristicWriteWithoutResponse)
            next.done.resume(true)
        }
    }

    private fun drain(link: InLink) {
        val ch = characteristic ?: return
        val server = server ?: return
        while (link.waiting.isNotEmpty()) {
            val next = link.waiting.first()
            if (next.done.isActive && !server.updateValue(next.frame.data(), ch, listOf(link.central))) return
            link.waiting.removeFirst()
            if (next.done.isActive) next.done.resume(true)
        }
    }

    private fun linkUp(link: OutLink) {
        val maxFrame = link.peripheral.maximumWriteValueLengthForType(CBCharacteristicWriteWithoutResponse).toInt()
        if (maxFrame < PacketCodec.MIN_FRAME) {
            log("link to ${link.address.takeLast(5)} refused: frames of $maxFrame bytes")
            return closeOut(link)
        }
        link.ready = true
        link.timeout?.cancel()
        channel.trySend(RadioEvent.LinkUp(link.id, link.address, true, maxFrame))
        // the server sends HELLO as soon as we subscribe, often before the subscription is confirmed here
        for (frame in link.early) channel.trySend(RadioEvent.Frame(link.id, frame))
        link.early.clear()
    }

    private fun received(link: OutLink, value: ByteArray) {
        if (link.closed) return
        if (!link.ready) {
            if (value.isEmpty()) closeOut(link) else if (link.early.size < MAX_EARLY_FRAMES) link.early += value
            return
        }
        // an empty notification means the server closed the link
        if (value.isEmpty()) closeOut(link) else channel.trySend(RadioEvent.Frame(link.id, value))
    }

    private fun outLink(peripheral: CBPeripheral): OutLink? = outgoing[peripheral.identifier.UUIDString]

    private inner class CentralEvents : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state == CBManagerStatePoweredOn) {
                restored.forEach(central::cancelPeripheralConnection)
                restored.clear()
                startScan()
            } else {
                outgoing.values.toList().forEach(::closeOut)
                forgetPeers()
            }
            publishStatus()
        }

        override fun centralManager(central: CBCentralManager, didDiscoverPeripheral: CBPeripheral, advertisementData: Map<Any?, *>, RSSI: NSNumber) {
            renewScan()
            heard(didDiscoverPeripheral, advertisementData, RSSI.intValue)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            renewScan()
            val link = outLink(didConnectPeripheral) ?: return
            log("connected ${link.address.takeLast(5)}")
            didConnectPeripheral.discoverServices(listOf(service))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            outLink(didFailToConnectPeripheral)?.let { closeOut(it) }
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            renewScan(linkLost = true)
            outLink(didDisconnectPeripheral)?.let { closeOut(it) }
        }

        /** After iOS ended the app in the background and started it again for Bluetooth: its links are the old engine's. */
        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            val peripherals = willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*> ?: return
            restored += peripherals.filterIsInstance<CBPeripheral>()
        }
    }

    private inner class LinkEvents : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val link = outLink(peripheral) ?: return
            val ours = peripheral.services?.firstOrNull { (it as? CBService)?.UUID == service } as? CBService
            if (didDiscoverServices != null || ours == null) {
                log("service missing on ${link.address.takeLast(5)}")
                return closeOut(link)
            }
            peripheral.discoverCharacteristics(listOf(characteristicId), ours)
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val link = outLink(peripheral) ?: return
            val ch = didDiscoverCharacteristicsForService.characteristics?.firstOrNull { (it as? CBCharacteristic)?.UUID == characteristicId } as? CBCharacteristic
            if (error != null || ch == null) return closeOut(link)
            link.characteristic = ch
            peripheral.setNotifyValue(true, ch)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            val link = outLink(peripheral) ?: return
            if (error != null || !didUpdateNotificationStateForCharacteristic.isNotifying) {
                log("subscription refused by ${link.address.takeLast(5)}")
                return closeOut(link)
            }
            if (!link.ready) linkUp(link)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            renewScan()
            val link = outLink(peripheral) ?: return
            if (error != null) return
            received(link, didUpdateValueForCharacteristic.value?.toBytes() ?: ByteArray(0))
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            outLink(peripheral)?.let(::drain)
        }

        /** The phone took the service down or published it anew (Bluetooth off and on, its app started again): ours is gone. */
        override fun peripheral(peripheral: CBPeripheral, didModifyServices: List<*>) {
            val link = outLink(peripheral) ?: return
            if (didModifyServices.any { (it as? CBService)?.UUID == service }) closeOut(link)
        }
    }

    private inner class PeripheralEvents : NSObject(), CBPeripheralManagerDelegateProtocol {
        override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
            if (peripheral.state == CBManagerStatePoweredOn) {
                openServer()
            } else {
                // the service is gone with Bluetooth: no farewell can be sent on it
                characteristic = null
                incoming.values.toList().forEach(::closeIn)
                farewells.clear()
                serviceAdded = false
                advertStarting = false
            }
            publishStatus()
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, didAddService: CBService, error: NSError?) {
            serviceAdded = error == null
            if (error != null) log("could not add the GATT service: ${error.localizedDescription}") else advertise()
        }

        override fun peripheralManagerDidStartAdvertising(peripheral: CBPeripheralManager, error: NSError?) {
            advertStarting = false
            // the flags may have changed while it started; a start that failed waits for the next change
            if (error != null) {
                log("advertising failed: ${error.localizedDescription}")
                publishStatus()
            } else {
                advertise()
            }
        }

        /** A phone subscribed: the link is up from here, as on Android's server. */
        @ObjCSignatureOverride
        override fun peripheralManager(peripheral: CBPeripheralManager, central: CBCentral, didSubscribeToCharacteristic: CBCharacteristic) {
            val address = central.identifier.UUIDString
            if (incoming.containsKey(address)) return
            val maxFrame = central.maximumUpdateValueLength.toInt()
            if (maxFrame < PacketCodec.MIN_FRAME) {
                log("link from ${address.takeLast(5)} refused: frames of $maxFrame bytes")
                return sayGoodbye(central)
            }
            val link = InLink(nextLink++, central)
            incoming[address] = link
            channel.trySend(RadioEvent.LinkUp(link.id, address, false, maxFrame))
        }

        @ObjCSignatureOverride
        override fun peripheralManager(peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFromCharacteristic: CBCharacteristic) {
            farewells.removeAll { it.identifier == central.identifier }
            renewScan(linkLost = true)
            val link = incoming.remove(central.identifier.UUIDString) ?: return
            while (link.waiting.isNotEmpty()) link.waiting.removeFirst().done.resume(false)
            channel.trySend(RadioEvent.LinkDown(link.id))
        }

        /** Writes without response, the only kind the characteristic takes: nothing to answer. */
        override fun peripheralManager(peripheral: CBPeripheralManager, didReceiveWriteRequests: List<*>) {
            renewScan()
            for (request in didReceiveWriteRequests) {
                val write = request as? CBATTRequest ?: continue
                if (write.characteristic.UUID != characteristicId || write.offset != 0uL) continue
                val link = incoming[write.central.identifier.UUIDString] ?: continue
                channel.trySend(RadioEvent.Frame(link.id, write.value?.toBytes() ?: continue))
            }
        }

        override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
            val goodbyes = farewells.toList()
            farewells.clear()
            goodbyes.forEach(::sayGoodbye)
            incoming.values.toList().forEach(::drain)
        }

        override fun peripheralManager(peripheral: CBPeripheralManager, willRestoreState: Map<Any?, *>) = Unit
    }

    private companion object {
        const val CENTRAL_ID = "ro.safetyplease.central"
        const val SERVER_ID = "ro.safetyplease.server"
        const val FORGET_EVERY_MS = 30_000L
        const val MAX_EARLY_FRAMES = 8

        const val RENEW_MS = 60_000L
        const val RENEW_AFTER_LOSS_MS = 8_000L

        /** Android's `BleConstants.WRITE_TIMEOUT_MS`. */
        const val WRITE_TIMEOUT_MS = 3_000L
    }
}

/**
 * The process's central and server, made by the first radio and handed on to each one after it (a new session, for
 * another event, makes a new radio): iOS restores a manager by its identifier, which must name one manager at a time,
 * and a manager let go lives on until it's collected. One queue serves them all, so a radio's stop comes before the
 * next one's start.
 */
@OptIn(ExperimentalForeignApi::class)
private object Managers {
    val queue = dispatch_queue_create("ro.safetyplease.ble", null)
    var central: CBCentralManager? = null
    var server: CBPeripheralManager? = null
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toBytes(): ByteArray = if (length.toInt() == 0) ByteArray(0) else bytes!!.readBytes(length.toInt())

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.data(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }
