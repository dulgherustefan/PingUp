package ro.safetyplease.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.venue.EvacuationPlans
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.designsystem.AppMenu
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.MenuRow
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.control_confirm_plan
import ro.safetyplease.ui.resources.control_confirm_zones
import ro.safetyplease.ui.resources.control_plan_exits
import ro.safetyplease.ui.resources.control_plan_label
import ro.safetyplease.ui.resources.control_plan_none
import ro.safetyplease.ui.resources.control_plan_rejected
import ro.safetyplease.ui.resources.control_zones_evacuation_note
import ro.safetyplease.ui.resources.control_zones_label
import ro.safetyplease.ui.resources.control_zones_note
import ro.safetyplease.ui.resources.notice_zone_all

/** The names of [zones] in the venue's order, or "Tot festivalul" for none. */
@Composable
fun zonesText(venue: Venue, zones: Set<String>): String =
    venue.zones.filter { it.id in zones }.joinToString(", ") { it.name }.ifEmpty { stringResource(Res.string.notice_zone_all) }

/**
 * The zones a notice is for, any number of them; none is the whole site. The phones in them get the alarm of a pause or
 * an evacuation, the others only see it in the list.
 */
@Composable
fun ZoneSetPicker(venue: Venue, zones: Set<String>, evacuation: Boolean, onChange: (Set<String>) -> Unit) {
    val colors = AppTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(
            stringResource(Res.string.control_zones_label), value = zonesText(venue, zones), chevron = true, onClick = { open = true },
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.cell),
        )
        AppMenu(open, { open = false }) {
            MenuRow(stringResource(Res.string.notice_zone_all), {
                onChange(emptySet())
                open = false
            }, icon = if (zones.isEmpty()) Sym.Check else null)
            venue.zones.forEach { zone ->
                val picked = zone.id in zones
                MenuRow(zone.name, { onChange(if (picked) zones - zone.id else zones + zone.id) }, icon = if (picked) Sym.Check else null)
            }
        }
    }
    val note = if (evacuation && zones.isEmpty()) Res.string.control_zones_evacuation_note else Res.string.control_zones_note
    Text(stringResource(note), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
}

/** The operator's pick of a plan for an evacuation: [id] null for none, the phones then listing every exit. */
data class PlanChoice(val id: Int?)

/**
 * The organiser's plan an evacuation starts: only those [EvacuationPlans.usable] passes are offered, each with its exits;
 * the others are named, so the operator knows why they are missing. Nothing is picked until the operator picks.
 */
@Composable
fun EvacuationPlanPicker(venue: Venue, plan: PlanChoice?, onChange: (PlanChoice) -> Unit) {
    val colors = AppTheme.colors
    val usable = remember(venue) { EvacuationPlans.usable(venue) }
    val rejected = remember(venue) { venue.evacuation.filter { it !in usable } }
    Text(stringResource(Res.string.control_plan_label), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
    InsetGroup {
        usable.forEachIndexed { i, p ->
            if (i > 0) GroupDivider(start = 16.dp)
            val exits = p.exits.values.flatMap { listOf(it.main, it.alternative) }.filter { it != 0 }.distinct().sorted()
            GroupRow(
                p.name, subtitle = stringResource(Res.string.control_plan_exits, exits.joinToString(", ")),
                icon = if (plan?.id == p.id) Sym.CheckCircle else null, tint = colors.accent, onClick = { onChange(PlanChoice(p.id)) },
            )
        }
        if (usable.isNotEmpty()) GroupDivider(start = 16.dp)
        GroupRow(
            stringResource(Res.string.control_plan_none), icon = if (plan != null && plan.id == null) Sym.CheckCircle else null,
            tint = colors.accent, onClick = { onChange(PlanChoice(null)) },
        )
    }
    if (rejected.isNotEmpty()) {
        Text(
            stringResource(Res.string.control_plan_rejected, rejected.joinToString(", ") { it.name }),
            style = MaterialTheme.typography.footnote, color = colors.red,
        )
    }
}

/** What the confirmation repeats besides the text: the zones and, for an evacuation, the plan. */
@Composable
fun targetsSummary(venue: Venue, zones: Set<String>, plan: Int?): String {
    val lines = listOfNotNull(
        stringResource(Res.string.control_confirm_zones, zonesText(venue, zones)),
        plan?.let(venue::evacuationPlan)?.let { stringResource(Res.string.control_confirm_plan, it.name) },
    )
    return lines.joinToString("\n")
}
