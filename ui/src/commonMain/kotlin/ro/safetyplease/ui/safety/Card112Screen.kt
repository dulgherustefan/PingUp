package ro.safetyplease.ui.safety

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.core.safety.Card112
import ro.safetyplease.core.safety.NearbyPlace
import ro.safetyplease.core.venue.GeoFix
import ro.safetyplease.core.venue.GeoPoint
import ro.safetyplease.core.venue.Venue
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.Banner
import ro.safetyplease.ui.designsystem.ButtonKind
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.headline
import ro.safetyplease.ui.designsystem.subheadline
import ro.safetyplease.ui.designsystem.title1
import ro.safetyplease.ui.designsystem.title2
import ro.safetyplease.ui.map.NavTarget
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.card112_accuracy
import ro.safetyplease.ui.resources.card112_accuracy_unknown
import ro.safetyplease.ui.resources.card112_at
import ro.safetyplease.ui.resources.card112_coordinates
import ro.safetyplease.ui.resources.card112_help_title
import ro.safetyplease.ui.resources.card112_landline
import ro.safetyplease.ui.resources.card112_medical
import ro.safetyplease.ui.resources.card112_near_landmark
import ro.safetyplease.ui.resources.card112_no_position_text
import ro.safetyplease.ui.resources.card112_no_position_title
import ro.safetyplease.ui.resources.card112_note
import ro.safetyplease.ui.resources.card112_outside_text
import ro.safetyplease.ui.resources.card112_outside_title
import ro.safetyplease.ui.resources.card112_pluscode
import ro.safetyplease.ui.resources.card112_say
import ro.safetyplease.ui.resources.card112_square
import ro.safetyplease.ui.resources.card112_stale_text
import ro.safetyplease.ui.resources.card112_stale_title
import ro.safetyplease.ui.resources.card112_title
import ro.safetyplease.ui.resources.emergency_call
import ro.safetyplease.ui.resources.route_go
import ro.safetyplease.ui.resources.safety_meters
import ro.safetyplease.ui.text.agoText
import ro.safetyplease.ui.text.rememberNow
import ro.safetyplease.ui.text.wallNowMs
import kotlin.math.roundToInt

/**
 * "Dacă suni 112, spune:" on the whole screen, to read aloud: where the festival and the spot are, the coordinates, the
 * Plus Code and the square of the site plan, and the nearest places to walk to for help. Without a fresh position it
 * says so and shows the last one, marked old.
 */
@Composable
fun Card112Screen(vm: AppViewModel) {
    val colors = AppTheme.colors
    val position by vm.position.collectAsStateWithLifecycle()
    val fix by vm.c.locationFix.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val now = rememberNow(5_000)
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    LifecycleResumeEffect(Unit) {
        vm.phone.startLocation()
        vm.phone.urgentLocation(true)
        onPauseOrDispose { vm.phone.urgentLocation(false) }
    }
    vm.phone.KeepScreenOn()

    // a fresh position, else the last one the phone had, marked old
    val last = fix
    val fresh = position
    val card = when {
        fresh != null -> Card112.of(vm.venue, vm.c.grid, fresh, cardAccuracyM(last, settings.simLat != null), 0, vm.c.walkDistance)
        last != null -> Card112.of(vm.venue, vm.c.grid, GeoPoint(last.lat, last.lon), cardAccuracyM(last, false), wallNowMs() - last.timeMs, vm.c.walkDistance)
        else -> null
    }

    NavScreen(
        title = stringResource(Res.string.card112_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(horizontal = Gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(Res.string.card112_say), style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel)
            if (card == null) {
                Banner(stringResource(Res.string.card112_no_position_title), stringResource(Res.string.card112_no_position_text), warning = true)
            } else {
                if (card.ageMin >= STALE_MIN) {
                    Banner(
                        stringResource(Res.string.card112_stale_title),
                        stringResource(Res.string.card112_stale_text, agoText(last?.timeMs ?: now, now)), warning = true,
                    )
                }
                val at = stringResource(Res.string.card112_at, vm.venue.name)
                val near = card.landmark?.let { stringResource(Res.string.card112_near_landmark, it) }
                val spoken = card.spoken(at, near)
                if (spoken != null) {
                    Text(spoken, style = MaterialTheme.typography.title1, fontWeight = FontWeight.SemiBold)
                } else {
                    Banner(stringResource(Res.string.card112_outside_title), stringResource(Res.string.card112_outside_text))
                }
                Fact(stringResource(Res.string.card112_coordinates), card.coordinates, mono = true)
                Text(
                    card.accuracyM?.let { stringResource(Res.string.card112_accuracy, it) } ?: stringResource(Res.string.card112_accuracy_unknown),
                    style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel,
                )
                Fact(stringResource(Res.string.card112_pluscode), card.plusCode, mono = true)
                card.gridCell?.let { Fact(stringResource(Res.string.card112_square), it) }
                if (card.landline != null || card.medical != null) {
                    Text(stringResource(Res.string.card112_help_title), style = MaterialTheme.typography.headline)
                    // the walk goes to the nearest open one from where the phone is then, around closed zones
                    card.landline?.let { Help(stringResource(Res.string.card112_landline), it) { vm.navigate(NavTarget.Nearest(Venue.PHONE)) } }
                    card.medical?.let { Help(stringResource(Res.string.card112_medical), it) { vm.navigate(NavTarget.Nearest(MEDICAL)) } }
                }
            }
            AppButton(stringResource(Res.string.emergency_call), { vm.phone.dial112() }, Modifier.fillMaxWidth(), icon = Sym.Phone)
            Text(stringResource(Res.string.card112_note), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        }
    }
}

/** A position older than this much is shown as old: the person may have moved since. */
private const val STALE_MIN = 2

/** The radius the phone gives the fix, as it is, however large; 0 (unknown) without a fix or for a simulated position. */
fun cardAccuracyM(fix: GeoFix?, simulated: Boolean): Int = if (fix == null || simulated) 0 else fix.accuracyM.roundToInt()

@Composable
private fun Fact(label: String, value: String, mono: Boolean = false) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.cell).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        Text(
            value, style = MaterialTheme.typography.title2, fontWeight = FontWeight.SemiBold,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun Help(label: String, place: NearbyPlace, onWalk: () -> Unit) {
    val colors = AppTheme.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.cell).padding(16.dp)) {
        Text(label, style = MaterialTheme.typography.footnote, color = colors.secondaryLabel)
        Text(
            listOf(place.name, stringResource(Res.string.safety_meters, place.meters)).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.title2,
        )
        AppButton(stringResource(Res.string.route_go), onWalk, Modifier.padding(top = 10.dp), kind = ButtonKind.Secondary, compact = true, icon = Sym.Walk)
    }
}

private const val MEDICAL = "medical"
