package ro.safetyplease.ui

import android.view.View
import java.util.WeakHashMap

private val holds = WeakHashMap<View, Int>()

/**
 * Keeps the screen on while anyone holds it: the evacuation, a walk, the 112 card and the console share the activity's
 * view, so one of them leaving must not let the screen sleep under another. Returns the release; main thread only.
 */
fun View.holdScreenOn(): () -> Unit {
    holds[this] = (holds[this] ?: 0) + 1
    keepScreenOn = true
    var released = false
    return {
        if (!released) {
            released = true
            val left = (holds[this] ?: 1) - 1
            if (left > 0) {
                holds[this] = left
            } else {
                holds.remove(this)
                keepScreenOn = false
            }
        }
    }
}
