package ro.safetyplease.core.util

interface Clock {
    /** Unix time in milliseconds; only for displayed timestamps and packet fields. */
    fun wallMs(): Long

    /** Monotonic time; for windows, expiry and pacing. */
    fun monoMs(): Long
}
