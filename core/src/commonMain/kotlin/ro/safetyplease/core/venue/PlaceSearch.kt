package ro.safetyplease.core.venue

import ro.safetyplease.core.event.ScheduleItem
import ro.safetyplease.core.util.decomposed

enum class FoundKind { LANDMARK, ZONE, PLACE, SET, FRIEND }

/**
 * A search result: a numbered landmark, a zone (a stage too), another useful point, a set (at its stage's [point]) or a
 * friend, at the position they last shared or nowhere.
 */
data class Found(
    val kind: FoundKind,
    val name: String,
    val point: GeoPoint?,
    val type: String = "",
    val number: Int = 0,
    val set: ScheduleItem? = null,
    val friend: Long = 0,
)

/** A friend as the search sees them. */
data class NamedPoint(val id: Long, val name: String, val point: GeoPoint?)

/**
 * The map's offline search: a number finds its landmark ("14" is Reperul 14), words find zones, places, sets and
 * friends by name, and a service in Romanian or English ("apa", "toilet") finds its places. Diacritics are optional
 * on both sides: "scena" finds "Scenă", "Ștefan" finds "Stefan".
 */
object PlaceSearch {
    const val MAX_RESULTS = 20

    private val NUMBER = Regex("""^(#|nr\.?|reper(ul)?|landmark)?\s*(\d{1,3})$""")

    /** Words for a type, already without diacritics: a festival-goer types what the sign says. */
    private val TYPE_WORDS = mapOf(
        "water" to listOf("apa", "water", "drinking"),
        "wc" to listOf("wc", "toaleta", "toalete", "toilet", "toilets", "bathroom"),
        "medical" to listOf("medical", "medic", "prim ajutor", "first aid"),
        Venue.DEA to listOf("dea", "aed", "defibrilator", "defibrillator"),
        "info" to listOf("info", "informare", "informatii", "information"),
        "charge" to listOf("incarcare", "charging", "charge", "baterie"),
        Venue.SHADE to listOf("umbra", "shade"),
        ServiceLayer.SAFE to listOf("safe", "safe zone", "siguranta", "welfare"),
        ServiceLayer.PHONE to listOf("telefon", "phone"),
        Venue.EXIT to listOf("iesire", "exit", "poarta", "gate"),
        ServiceLayer.LOCKERS to listOf("vestiar", "dulap", "locker", "lockers"),
        ServiceLayer.QUIET to listOf("liniste", "linistit", "quiet", "calm", "relaxare", "chill"),
        ServiceLayer.EARPLUGS to listOf("dopuri", "dopuri urechi", "antifoane", "earplugs", "ear plugs"),
        "stage" to listOf("scena", "stage"),
    )

    /** Lower case, without diacritics: "Scenă" and "SCENA" read the same. */
    fun normalize(text: String): String =
        text.decomposed().filterNot { it in COMBINING_MARKS }.lowercase().trim()

    /** How well [name] answers [query], both normalized: 0 best, null not at all. */
    internal fun score(name: String, query: String): Int? {
        if (query.isEmpty() || name.isEmpty()) return null
        if (name == query) return 0
        if (name.startsWith(query)) return 1
        val words = splitWords(name)
        val tokens = splitWords(query)
        if (tokens.isNotEmpty() && tokens.all { t -> words.any { it.startsWith(t) } }) return 2
        if (query.length >= 3 && query in name) return 3
        return null
    }

    fun search(
        query: String,
        venue: Venue,
        schedule: List<ScheduleItem> = emptyList(),
        friends: List<NamedPoint> = emptyList(),
        nowSec: Long = 0,
    ): List<Found> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Int, Found>>()
        NUMBER.matchEntire(q)?.let { m ->
            val number = m.groupValues[3].toInt()
            venue.landmark(number)?.let { scored += 0 to Found(FoundKind.LANDMARK, it.name, it.point, it.type, number) }
        }
        val types = if (q.length < 2) emptySet() else TYPE_WORDS.filterValues { words -> words.any { w -> score(w, q) != null } }.keys
        for (zone in venue.zones) {
            val s = score(normalize(zone.name), q) ?: if (zone.type in types) TYPE_SCORE else null
            if (s != null) scored += s to Found(FoundKind.ZONE, zone.name, zone.center, zone.type)
        }
        for (poi in venue.pois) {
            val s = score(normalize(poi.name), q) ?: if (poi.type in types) TYPE_SCORE else null
            if (s == null || scored.any { it.second.kind == FoundKind.LANDMARK && it.second.number == poi.number && poi.number > 0 }) continue
            val kind = if (poi.number in Venue.LANDMARK_NUMBERS) FoundKind.LANDMARK else FoundKind.PLACE
            scored += s to Found(kind, poi.name, poi.point, poi.type, poi.number)
        }
        // what is on or still to come before what has ended
        for (item in schedule.sortedWith(compareBy<ScheduleItem> { it.end <= nowSec }.thenBy { it.start })) {
            val s = score(normalize(item.title), q) ?: continue
            val zone = venue.zone(item.stage)
            scored += (s + if (item.end <= nowSec) ENDED_PENALTY else 0) to
                Found(FoundKind.SET, item.title, zone?.center, "stage", set = item)
        }
        for (friend in friends) {
            val s = score(normalize(friend.name), q) ?: continue
            scored += s to Found(FoundKind.FRIEND, friend.name, friend.point, friend = friend.id)
        }
        return scored.withIndex()
            .sortedWith(compareBy({ it.value.first }, { it.value.second.kind.ordinal }, { it.index }))
            .map { it.value.second }
            .take(MAX_RESULTS)
    }

    /** The combining diacritical marks block, what decomposing "ă" leaves after the "a". */
    private val COMBINING_MARKS = '\u0300'..'\u036F'

    private fun splitWords(text: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit()) {
                word.append(c)
            } else if (word.isNotEmpty()) {
                words += word.toString()
                word.clear()
            }
        }
        if (word.isNotEmpty()) words += word.toString()
        return words
    }

    private const val TYPE_SCORE = 4
    private const val ENDED_PENALTY = 10
}
