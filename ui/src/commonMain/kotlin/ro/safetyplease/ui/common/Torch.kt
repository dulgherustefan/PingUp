package ro.safetyplease.ui.common

import kotlinx.coroutines.flow.StateFlow

/** The phone's flash as a torch, so a team can spot the person who reported. */
interface Torch {
    /** The phone has a flash it can light. */
    val available: Boolean

    /** Lit, by this app or another. */
    val on: StateFlow<Boolean>

    /** Lights it or puts it out; false when the phone didn't. */
    fun set(on: Boolean): Boolean

    /** Follows the flash while it's shown. */
    fun watch()

    /** Stops following it and puts out only what this app lit. */
    fun release()
}
