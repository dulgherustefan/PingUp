package ro.safetyplease.core.mesh

/** How aggressively the phone scans and advertises, based on battery and pending incidents. */
object PowerPolicy {
    const val LOW_BATTERY_PERCENT = 20
    const val BOOST_MS = 15_000L

    /** Returns (scan mode, advertise mode). [boost] covers the 15 s after an incident is sent. */
    fun modes(batteryPercent: Int, charging: Boolean, pendingIncident: Boolean, boost: Boolean): Pair<PowerMode, PowerMode> {
        val saving = batteryPercent < LOW_BATTERY_PERCENT && !charging
        val base = if (saving) PowerMode.LOW_POWER else PowerMode.BALANCED
        val scan = if (boost) PowerMode.LOW_LATENCY else base
        val advertise = if (boost || pendingIncident) PowerMode.LOW_LATENCY else base
        return scan to advertise
    }
}
