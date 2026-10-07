package ro.safetyplease.app.platform.ble

import android.bluetooth.le.AdvertisingSetCallback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LinkOpsTest {
    private enum class Op { MTU, DISCOVER }

    // --- legatura picata in timpul configurarii ---

    @Test
    fun opCompletesWhenTheCallbackArrives() = runTest {
        val ops = GattOps<Op>()
        val result = async { ops.run(Op.MTU, 5_000) { true } }
        runCurrent()
        ops.finish(Op.MTU, true)
        assertTrue(result.await())
    }

    @Test
    fun callbackForAnotherOpIsIgnored() = runTest {
        val ops = GattOps<Op>()
        val result = async { ops.run(Op.MTU, 5_000) { true } }
        runCurrent()
        ops.finish(Op.DISCOVER, true)
        advanceTimeBy(5_001)
        assertFalse(result.await())
    }

    @Test
    fun dropBetweenOpsFailsTheNextOneImmediately() = runTest {
        val ops = GattOps<Op>()
        // deconectarea vine cand nicio operatie nu e in zbor, de exemplu in pauza de dupa conectare
        ops.drop()
        var started = false
        assertFalse(ops.run(Op.MTU, 5_000) { started = true; true })
        assertFalse(started)
        assertEquals(0L, currentTime)
    }

    @Test
    fun dropDuringAnOpEndsItWithoutWaitingForTheTimeout() = runTest {
        val ops = GattOps<Op>()
        val result = async { ops.run(Op.MTU, 5_000) { true } }
        runCurrent()
        ops.drop()
        assertFalse(result.await())
        assertEquals(0L, currentTime)
        assertTrue(ops.dropped)
    }

    @Test
    fun dropAfterSuccessBeforeResumeStillFails() = runTest {
        val ops = GattOps<Op>()
        val result = async { ops.run(Op.DISCOVER, 5_000) { true } }
        runCurrent()
        ops.finish(Op.DISCOVER, true)
        ops.drop()
        assertFalse(result.await())
    }

    @Test
    fun stackRefusalFailsAtOnce() = runTest {
        val ops = GattOps<Op>()
        assertFalse(ops.run(Op.MTU, 5_000) { false })
        assertFalse(ops.run(Op.MTU, 5_000) { error("binder") })
        assertEquals(0L, currentTime)
        assertFalse(ops.dropped)
    }

    // --- congestie ---

    @Test
    fun congestedCountsAsSent() {
        assertTrue(sendAccepted(0))
        assertTrue(sendAccepted(143))
    }

    @Test
    fun realErrorsStillFail() {
        assertFalse(sendAccepted(0x81))
        assertFalse(sendAccepted(133))
        assertFalse(sendAccepted(257))
    }

    // --- advertising care pica ---

    @Test
    fun threeFailuresDemoteAndSlowTheRetries() {
        val f = AdvertiseFailures()
        assertEquals(BleConstants.ADVERTISE_RETRY_MS, f.failed(AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS))
        assertEquals(BleConstants.ADVERTISE_RETRY_MS, f.failed(AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR))
        assertFalse(f.demoted)
        assertEquals(BleConstants.ADVERTISE_DEMOTED_RETRY_MS, f.failed(AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS))
        assertTrue(f.demoted)
    }

    @Test
    fun alreadyStartedIsNotAFailure() {
        val f = AdvertiseFailures()
        repeat(5) { f.failed(AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED) }
        assertEquals(0, f.count)
        assertFalse(f.demoted)
    }

    @Test
    fun successRestoresAdvertising() {
        val f = AdvertiseFailures()
        repeat(4) { f.failed(AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS) }
        assertTrue(f.demoted)
        f.reset()
        assertFalse(f.demoted)
        assertEquals(BleConstants.ADVERTISE_RETRY_MS, f.failed(AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS))
    }
}
