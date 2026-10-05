package ro.safetyplease.app.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R

/** Curba iOS pentru intrarea unui ecran: porneste repede si se aseaza lin. */

/** Marginea paginilor de inceput, mai larga decat a listelor, ca la inregistrarea din Signal. */
private val PageGutter = 24.dp

/**
 * Pornirea, in doi pasi, ca inregistrarea din Signal pe iPhone: numele (cu bula lui care se deseneaza pe masura
 * ce scrii), apoi ce permisiuni cerem si de ce. Restul permisiunilor se cer cand e nevoie de ele.
 */
@Composable
fun OnboardingScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    val reduce = LocalReduceMotion.current
    val focus = LocalFocusManager.current
    val colors = AppTheme.colors
    val finish = {
        vm.finishOnboarding(name)
        onStartMesh()
    }
    val next = {
        // tastatura coboara cat intra pagina urmatoare, nu dupa
        focus.clearFocus()
        step = 1
    }
    // oricare ar fi raspunsul, omul intra in aplicatie; ce lipseste apare acolo ca un banner cu buton
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { finish() }
    BackHandler(enabled = step == 1) { step = 0 }

    AnimatedContent(
        targetState = step,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val push = Motion.Push
            when {
                reduce -> fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))
                // pagina noua intra din dreapta peste cea veche, care se da o treime la stanga
                targetState > initialState -> slideInHorizontally(push) { it } togetherWith slideOutHorizontally(push) { -it / 3 }
                else -> (slideInHorizontally(push) { -it / 3 } togetherWith slideOutHorizontally(push) { it }).apply { targetContentZIndex = -1f }
            }
        },
        label = "onboarding",
    ) { current ->
        if (current == 0) {
            StepPage(
                onBack = null,
                bottom = { AppButton(stringResource(R.string.next), next, Modifier.fillMaxWidth(), enabled = name.isNotBlank()) },
            ) {
                AppLogo(96.dp, Modifier.padding(bottom = 20.dp))
                Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.largeTitle, color = colors.label)
                Text(
                    stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.body, color = colors.secondaryLabel,
                    modifier = Modifier.padding(top = 12.dp),
                )
                Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                    if (name.isBlank()) {
                        Box(Modifier.size(72.dp).clip(CircleShape).background(colors.fill), contentAlignment = Alignment.Center) {
                            Icon(Sym.Person, null, Modifier.size(36.dp), tint = colors.secondaryLabel)
                        }
                    } else {
                        Avatar(name, 72.dp)
                    }
                }
                InputField(
                    name, { name = it.take(MAX_NAME) }, stringResource(R.string.name_placeholder), Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) next() }),
                    supporting = stringResource(R.string.name_label) + ". " + stringResource(R.string.name_hint),
                    background = colors.fill,
                )
            }
        } else {
            StepPage(
                onBack = { step = 0 },
                bottom = {
                    AppButton(stringResource(R.string.onboarding_start), { permissions.launch(startPermissions()) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    TextLink(stringResource(R.string.onboarding_not_now), finish)
                },
            ) {
                Text(stringResource(R.string.onboarding_permissions), style = MaterialTheme.typography.largeTitle, color = colors.label)
                Text(
                    stringResource(R.string.onboarding_permissions_text), style = MaterialTheme.typography.body, color = colors.secondaryLabel,
                    modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Reason(Sym.Bluetooth, stringResource(R.string.perm_bluetooth_title), stringResource(R.string.perm_bluetooth_text))
                    Reason(Sym.Bell, stringResource(R.string.perm_notifications_title), stringResource(R.string.perm_notifications_text))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Reason(Sym.Place, stringResource(R.string.perm_location_title), stringResource(R.string.perm_location_text))
                    }
                }
                Hint(stringResource(R.string.onboarding_later), Modifier.padding(top = 28.dp))
            }
        }
    }
}

/**
 * O pagina de inceput, opaca, ca sa acopere pagina de dedesubt cat aluneca: sus locul barei (cu inapoi, daca are),
 * apoi continutul care deruleaza, iar jos butoanele, prinse deasupra tastaturii.
 */
@Composable
private fun StepPage(onBack: (() -> Unit)?, bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(AppTheme.colors.background).safeDrawingPadding().imePadding()) {
        Box(Modifier.fillMaxWidth().height(NavHeight).padding(horizontal = Gutter), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) GlassIconButton(Sym.Back, stringResource(R.string.back), onBack, null)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = PageGutter, vertical = 12.dp),
            content = content,
        )
        Column(
            Modifier.fillMaxWidth().padding(start = PageGutter, end = PageGutter, top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, content = bottom,
        )
    }
}

/** O permisiune: iconita in cercul verde, ce e si de ce o cerem. */
@Composable
private fun Reason(icon: ImageVector, title: String, text: String) {
    val colors = AppTheme.colors
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        IconCircle(icon, colors.accent, colors.onAccent, size = 44.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headline, color = colors.label)
            Text(text, style = MaterialTheme.typography.subheadline, color = colors.secondaryLabel, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
