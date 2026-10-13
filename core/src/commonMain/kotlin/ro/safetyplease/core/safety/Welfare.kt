package ro.safetyplease.core.safety

import ro.safetyplease.core.incidents.Discreet

/**
 * "Nu sunt bine": someone who feels unwell in their mind (anxiety, panic, overwhelmed) asks the welfare team for
 * company. It is a discreet report ([Discreet]), so the routing already sends it to the safeguarding role and nothing
 * new travels on the wire; a second mark after the first lets the console tell it from a request about someone else's
 * behaviour, e.g. in the hotspots.
 */
object Welfare {
    /** U+1F499 BLUE HEART and a space, right after the discreet mark. */
    const val MARK = "💙 "

    fun describe(text: String): String = Discreet.describe(MARK + text)

    fun isWelfare(description: String): Boolean = description.startsWith(Discreet.MARK + MARK)
}
