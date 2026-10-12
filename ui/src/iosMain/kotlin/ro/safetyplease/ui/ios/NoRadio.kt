package ro.safetyplease.ui.ios

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import ro.safetyplease.core.mesh.PowerMode
import ro.safetyplease.core.mesh.PowerPlan
import ro.safetyplease.core.mesh.RadioEvent
import ro.safetyplease.core.mesh.RadioStatus
import ro.safetyplease.core.session.MeshRadio

/** No Bluetooth yet: the radio says it's off, takes no link and sends nothing. */
class NoRadio : MeshRadio {
    private val channel = Channel<RadioEvent>(Channel.UNLIMITED)

    override val events: ReceiveChannel<RadioEvent> = channel

    override fun start() {
        channel.trySend(RadioEvent.Status(RadioStatus(bluetoothOn = false, canAdvertise = false)))
    }

    override fun stop() = Unit

    override fun setPower(plan: PowerPlan) = Unit

    override fun retry() = start()

    override fun connect(address: String, timeoutMs: Long) {
        channel.trySend(RadioEvent.ConnectFailed(address))
    }

    override fun disconnect(link: Int) = Unit

    override suspend fun send(link: Int, frame: ByteArray) = false

    override fun setAdvertising(flags: Int, prefix: Int) = Unit

    override fun setPowerMode(scan: PowerMode, advertise: PowerMode) = Unit
}
