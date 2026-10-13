package ro.safetyplease.core.incidents

/**
 * The discreet report "mă simt în nesiguranță" (roadmap 3.17): a harassment report that staff reads as a request to the
 * safeguarding team. It needs no field of its own: it is the first thing in the description, so any staff phone shows
 * it as text and the routing knows it. The report is sealed like any other and the reporter's phone advertises nothing
 * about it.
 */
object Discreet {
    /** U+1F92B SHUSHING FACE and a space. */
    const val MARK = "🤫 "

    /** The role that answers it. */
    const val ROLE = "Safeguarding"

    fun describe(text: String): String = MARK + text

    fun isDiscreet(description: String): Boolean = description.startsWith(MARK)
}
