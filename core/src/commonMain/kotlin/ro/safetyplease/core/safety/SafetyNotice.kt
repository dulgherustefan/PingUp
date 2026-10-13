package ro.safetyplease.core.safety

/** What the safety features tell the person from the background; the phone words it and gives it its actions. */
sealed interface SafetyNotice {
    /** The check-in's time is up: "Ai ajuns?", with "Am ajuns" and "+15 / +30 min" to answer it. */
    data class CheckinDue(val label: String) : SafetyNotice

    /** Nobody answered in time: [friends] of the chosen ones were told (0 when none is a friend any more), and the person can still say they arrived. */
    data class CheckinAlerted(val label: String, val friends: Int) : SafetyNotice

    /** The check-in ended or was extended: the notice above goes. */
    data object CheckinCleared : SafetyNotice

    /** [name] did not check in at [label] by [deadlineMs]; their last position is from [fixedAtMs], 0 without one. */
    data class FriendSilent(val name: String, val label: String, val deadlineMs: Long, val fixedAtMs: Long) : SafetyNotice
}
