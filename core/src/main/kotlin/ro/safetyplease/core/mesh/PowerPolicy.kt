package ro.safetyplease.core.mesh

/** Cat de agresiv scaneaza si face advertising telefonul, in functie de baterie si de incidentele in curs. */
object PowerPolicy {
    const val LOW_BATTERY_PERCENT = 20
    const val BOOST_MS = 15_000L

    /** Intoarce (mod de scanare, mod de advertising). [boost] acopera cele 15 s de dupa trimiterea unui incident. */
    fun modes(batteryPercent: Int, charging: Boolean, pendingIncident: Boolean, boost: Boolean): Pair<PowerMode, PowerMode> {
        val saving = batteryPercent < LOW_BATTERY_PERCENT && !charging
        val base = if (saving) PowerMode.LOW_POWER else PowerMode.BALANCED
        val scan = if (boost) PowerMode.LOW_LATENCY else base
        val advertise = if (boost || pendingIncident) PowerMode.LOW_LATENCY else base
        return scan to advertise
    }
}
