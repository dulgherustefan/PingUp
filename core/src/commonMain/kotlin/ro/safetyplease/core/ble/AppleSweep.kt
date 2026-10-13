package ro.safetyplease.core.ble

import ro.safetyplease.core.mesh.PowerProfile

/**
 * A short scan for every Apple advertisement, beside the scan for our service. An iPhone with the app in the background
 * puts our UUID in its overflow area ([OverflowArea]), but on iOS 27 that area is often not the last Apple block of the
 * advertisement, the only one Android's filters compare: the filter on our bit then misses the iPhone, and only the raw
 * bytes show it. Taking every Apple advertisement all the time would bring thousands of results a second in a festival
 * crowd, so the sweep lasts a few seconds a minute, and not at all on a low battery or on an anchor, which doesn't
 * connect to such iPhones.
 */
object AppleSweep {
    const val EVERY_MS = 60_000L

    /** At full rate: an iPhone advertises less often in the background. */
    const val FOR_MS = 4_000L

    fun wanted(profile: PowerProfile?): Boolean = profile != PowerProfile.ECONOMY && profile != PowerProfile.ANCHOR
}
