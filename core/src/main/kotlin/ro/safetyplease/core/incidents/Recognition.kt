package ro.safetyplease.core.incidents

/**
 * "Cum te recunoaștem?": what tells the reporter apart in the crowd (a red shirt, a flag, a raised phone torch). It
 * travels as the first line of the description, after [MARK], so a phone that doesn't know it still shows it as text,
 * and one that does shows it apart from the reporter's words.
 */
object Recognition {
    /** U+1F440 EYES and a space: "look for", where the line is shown as it came. */
    const val MARK = "👀 "

    /** A few words, not a second description. */
    const val MAX_CHARS = 40

    private val Spaces = Regex("\\s+")

    /** The description carrying [hint] on one line before [text]; just [text] without a hint. */
    fun join(hint: String, text: String): String {
        var line = hint.replace(Spaces, " ").trim()
        if (line.length > MAX_CHARS) {
            // never half of a character outside the Basic Multilingual Plane
            val end = if (Character.isHighSurrogate(line[MAX_CHARS - 1])) MAX_CHARS - 1 else MAX_CHARS
            line = line.substring(0, end).trim()
        }
        if (line.isEmpty()) return text
        return if (text.isEmpty()) MARK + line else "$MARK$line\n$text"
    }

    /** The hint and the reporter's words; the hint is empty when the description carries none. */
    fun split(description: String): Pair<String, String> {
        if (!description.startsWith(MARK)) return "" to description
        val rest = description.removePrefix(MARK)
        return rest.substringBefore('\n').trim() to rest.substringAfter('\n', "")
    }
}
