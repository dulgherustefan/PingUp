package ro.safetyplease.core.mesh

/**
 * Our own on/off cycle on top of the stack's scan mode: scan for [windowMs] out of every [intervalMs].
 * From Android 13 the stack lowers every scan mode except LOW_LATENCY when the screen is off; this cycle works the
 * same with the screen on or off, and the pause also saves the wakeups for every advertisement heard.
 */
data class ScanDuty(val windowMs: Long, val intervalMs: Long) {
    init {
        require(windowMs in 1..intervalMs) { "window $windowMs outside 1..$intervalMs" }
    }

    val continuous: Boolean get() = windowMs == intervalMs
    val offMs: Long get() = intervalMs - windowMs
    val fraction: Double get() = windowMs.toDouble() / intervalMs

    /** Scan starts this cycle needs in [periodMs]; Android refuses more than 5 in 30 s. */
    fun startsIn(periodMs: Long): Long = if (continuous) 0 else (periodMs + intervalMs - 1) / intervalMs

    override fun toString(): String = if (continuous) "continuous" else "${windowMs}/${intervalMs} ms"

    companion object {
        val CONTINUOUS = ScanDuty(1, 1)
    }
}

enum class PowerProfile { LEAF, STAFF, ANCHOR, ECONOMY }

/** What the radio applies: the stack's scan and advertise modes and our scan duty cycle. */
data class PowerPlan(val profile: PowerProfile, val scan: PowerMode, val advertise: PowerMode, val duty: ScanDuty)

/**
 * How aggressively the phone scans and advertises, from its role, the battery, the screen and a report just sent.
 *
 * What the stack makes of each scan mode, as window/interval in ms (AOSP defaults; vendors and `Settings.Global` can
 * change them). Android 8 has 500/5000, 2000/5000 and 5000/5000. Before 13 a filtered scan like ours keeps its mode
 * with the screen off; from 13 a foreground app's (a foreground service counts) is lowered, except LOW_LATENCY, and a
 * background app's gets 512/10240. Batched scans use their own, lower numbers.
 *
 * | Mode        | Android 9–12 | Android 13+ | Android 13+, screen off |
 * |-------------|--------------|-------------|-------------------------|
 * | LOW_POWER   | 512/5120     | 140/1400    | 512/10240               |
 * | BALANCED    | 1024/4096    | 183/730     | 183/730                 |
 * | LOW_LATENCY | 4096/4096    | 100/100     | 100/100                 |
 */
object PowerPolicy {
    const val LOW_BATTERY_PERCENT = 20

    /** Under this the phone shows text only: no map and no animations. */
    const val TEXT_ONLY_PERCENT = 5

    const val BOOST_MS = 15_000L

    /** The 12 s pause stays under [ConnectionPolicy.FRESH_MS], so a peer heard in one window is still fresh in the next. */
    val LEAF_DUTY = ScanDuty(windowMs = 13_000, intervalMs = 25_000)
    val ECONOMY_DUTY = ScanDuty(windowMs = 13_000, intervalMs = 25_000)

    fun lowBattery(batteryPercent: Int, charging: Boolean): Boolean = batteryPercent < LOW_BATTERY_PERCENT && !charging

    fun textOnly(batteryPercent: Int, charging: Boolean): Boolean = batteryPercent < TEXT_ONLY_PERCENT && !charging

    fun profile(staff: Boolean, anchor: Boolean, batteryPercent: Int, charging: Boolean): PowerProfile = when {
        lowBattery(batteryPercent, charging) -> PowerProfile.ECONOMY
        anchor -> PowerProfile.ANCHOR
        staff -> PowerProfile.STAFF
        // participants near a verified staff phone or anchor become leaves; until roles are verified, all of them
        else -> PowerProfile.LEAF
    }

    /**
     * [boost] covers the 15 s after a report is sent: LOW_LATENCY scanning, the expensive mode, and only then.
     * [reportPending] (our report hasn't reached staff) and a lit screen on a leaf keep the scan from pausing.
     * Advertising depends on the profile alone: it never changes because of a report, so the reporter doesn't stand out.
     */
    fun plan(
        staff: Boolean,
        anchor: Boolean,
        batteryPercent: Int,
        charging: Boolean,
        screenOn: Boolean,
        boost: Boolean,
        reportPending: Boolean,
    ): PowerPlan {
        val profile = profile(staff, anchor, batteryPercent, charging)
        val base = when (profile) {
            // in a crowd, 1 s advertising leaves the channels far less crowded than 250 ms
            PowerProfile.LEAF -> PowerPlan(profile, PowerMode.BALANCED, PowerMode.LOW_POWER, LEAF_DUTY)
            PowerProfile.STAFF, PowerProfile.ANCHOR ->
                PowerPlan(profile, PowerMode.BALANCED, PowerMode.BALANCED, ScanDuty.CONTINUOUS)
            PowerProfile.ECONOMY -> PowerPlan(profile, PowerMode.LOW_POWER, PowerMode.LOW_POWER, ECONOMY_DUTY)
        }
        // the lit screen costs far more than the scan; a leaf someone is looking at answers faster
        val screenKeepsOn = screenOn && profile == PowerProfile.LEAF
        return when {
            boost -> base.copy(scan = PowerMode.LOW_LATENCY, duty = ScanDuty.CONTINUOUS)
            reportPending || screenKeepsOn -> base.copy(duty = ScanDuty.CONTINUOUS)
            else -> base
        }
    }
}
