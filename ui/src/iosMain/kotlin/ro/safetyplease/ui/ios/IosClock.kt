package ro.safetyplease.ui.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime_nsec_np
import ro.safetyplease.core.util.Clock

/** The wall clock, and one that only goes forward and keeps counting while the phone sleeps, like Android's elapsedRealtime. */
@OptIn(ExperimentalForeignApi::class)
object IosClock : Clock {
    override fun wallMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    override fun monoMs(): Long = (clock_gettime_nsec_np(CLOCK_MONOTONIC.convert()) / 1_000_000u).toLong()
}
