package ro.safetyplease.ui.common

/**
 * What the phone does beside the torch while a friend looks for it ("Fă-te văzut"): it vibrates in the torch's rhythm
 * and, if the person chose sound, beeps with each flash.
 */
interface Beacon {
    /** Vibrates in [pattern]: milliseconds off, on, off, on... */
    fun vibrate(pattern: LongArray)

    /** A beep of [ms] milliseconds; nothing without sound. */
    fun beep(ms: Int)

    /** Stops the vibration and lets the sound go. */
    fun release()
}
