package ro.safetyplease.ui.text

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.announce.Notice
import ro.safetyplease.core.announce.NoticeTopic
import ro.safetyplease.core.announce.ZoneState
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_body_plan
import ro.safetyplease.ui.resources.control_body_zones
import ro.safetyplease.ui.resources.control_map_all_open
import ro.safetyplease.ui.resources.control_map_closed_list
import ro.safetyplease.ui.resources.control_map_crowded_list
import ro.safetyplease.ui.resources.control_map_exit_list

/**
 * The words of what the map editor and the evacuation plans add to a notice: the zones a notice is for, the plan an
 * evacuation names and, for the map state, the zones that aren't open. The same on the screens, in the notifications
 * and in the console log; each caller fills a text in its own way ([format]).
 */
object ControlNoticeText {
    /** Every text [body] may fill, to read once without arguments and [fill] in. */
    val RESOURCES = listOf(
        Res.string.control_body_zones, Res.string.control_body_plan, Res.string.control_map_all_open,
        Res.string.control_map_closed_list, Res.string.control_map_crowded_list, Res.string.control_map_exit_list, NoticeText.inZone,
    )

    /** [text] with the zone set and the plan, or the map state in words; null when the notice has none of them. */
    fun body(notice: Notice, venue: Venue, text: String, format: (StringResource, Array<String>) -> String): String? {
        if (notice.cancelled) return null
        if (notice.topic == NoticeTopic.MAP_STATE) return mapState(notice, venue, format)
        val zones = notice.extras.zones?.ids(venue)?.takeIf { it.isNotEmpty() }
        val plan = notice.extras.plan?.let(venue::evacuationPlan)
        if (zones == null && plan == null) return null
        val names = zones?.let { ids -> venue.zones.filter { it.id in ids }.joinToString(", ") { it.name } }
        val targeted = when {
            names != null -> format(Res.string.control_body_zones, arrayOf(names, text))
            notice.zone.isNotEmpty() -> format(NoticeText.inZone, arrayOf(venue.zoneName(notice.zone), text))
            else -> text
        }
        return if (plan == null) targeted else format(Res.string.control_body_plan, arrayOf(targeted, plan.name))
    }

    private fun mapState(notice: Notice, venue: Venue, format: (StringResource, Array<String>) -> String): String? {
        val states = notice.extras.states?.takeIf { it.size == venue.zones.size } ?: return null
        fun named(state: ZoneState) = venue.zones.zip(states).filter { it.second == state }.joinToString(", ") { it.first.name }
        val lines = listOf(
            ZoneState.CLOSED to Res.string.control_map_closed_list,
            ZoneState.EXIT_ONLY to Res.string.control_map_exit_list,
            ZoneState.CROWDED to Res.string.control_map_crowded_list,
        ).mapNotNull { (state, res) -> named(state).takeIf { it.isNotEmpty() }?.let { format(res, arrayOf(it)) } }
        return if (lines.isEmpty()) format(Res.string.control_map_all_open, emptyArray()) else lines.joinToString("; ")
    }

    /** `%1$s` and on, replaced as Compose does, for a text read without its arguments. */
    fun fill(text: String, args: Array<String>): String =
        args.foldIndexed(text) { i, acc, arg -> acc.replace("%${i + 1}\$s", arg) }

    /** The same, in a composable: the texts read here, filled without another composition. */
    @Composable
    fun formatter(): (StringResource, Array<String>) -> String {
        val raw = RESOURCES.associateWith { stringResource(it) }
        return { res, args -> fill(raw.getValue(res), args) }
    }
}
