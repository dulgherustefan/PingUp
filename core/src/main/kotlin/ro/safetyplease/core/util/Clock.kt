package ro.safetyplease.core.util

interface Clock {
    /** Timp unix in milisecunde; doar pentru timestamp-uri afisate si puse in pachete. */
    fun wallMs(): Long

    /** Timp monoton; pentru ferestre, expirari si pacing. */
    fun monoMs(): Long
}
