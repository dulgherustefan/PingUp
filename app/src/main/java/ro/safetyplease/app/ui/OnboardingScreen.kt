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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R

/** Pornirea, in doua ecrane: cum te vad prietenii, apoi ce cerem si de ce. Restul permisiunilor se cer cand e nevoie de ele. */
@Composable
fun OnboardingScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    val colors = LocalAppColors.current
    val reduce = LocalReduceMotion.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // oricare ar fi raspunsul, omul intra in aplicatie; ce lipseste apare acolo ca un card cu buton
        vm.finishOnboarding(name)
        onStartMesh()
    }
    BackHandler(enabled = step == 1) { step = 0 }

    AnimatedContent(
        targetState = step,
        modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            if (reduce) fadeIn(tween(Motion.QUICK)) togetherWith fadeOut(tween(90))
            else (fadeIn(tween(Motion.STANDARD)) + slideInHorizontally(tween(Motion.STANDARD, easing = Motion.Enter)) { direction * it / 8 }) togetherWith
                (fadeOut(tween(100)) + slideOutHorizontally(tween(Motion.QUICK, easing = Motion.Exit)) { -direction * it / 8 })
        },
        label = "onboarding",
    ) { current ->
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
          // continutul deruleaza, butonul ramane jos, la indemana
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (current == 0) {
                Spacer(Modifier.height(48.dp))
                HopLine(Modifier.width(196.dp), dots = 6, dotSize = 11.dp)
                Spacer(Modifier.height(36.dp))
                Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.displaySmall, color = colors.text)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary)
                Spacer(Modifier.height(36.dp))
                SectionLabel(stringResource(R.string.name_label), Modifier.padding(start = 4.dp, bottom = 10.dp))
                PillTextField(
                    name, { name = it.take(MAX_NAME) }, stringResource(R.string.name_placeholder), Modifier.fillMaxWidth(),
                    leading = AppIcons.Person,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) step = 1 }),
                )
                Text(
                    stringResource(R.string.name_hint), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            } else {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.onboarding_permissions), style = MaterialTheme.typography.headlineMedium, color = colors.text)
                Spacer(Modifier.height(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Reason(AppIcons.Bluetooth, stringResource(R.string.perm_bluetooth_title), stringResource(R.string.perm_bluetooth_text))
                    Reason(AppIcons.Bell, stringResource(R.string.perm_notifications_title), stringResource(R.string.perm_notifications_text))
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                        Reason(AppIcons.Place, stringResource(R.string.perm_location_title), stringResource(R.string.perm_location_text))
                    }
                }
                Text(
                    stringResource(R.string.onboarding_later), style = MaterialTheme.typography.bodySmall, color = colors.textSecondary,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp),
                )
            }
          }
          Spacer(Modifier.height(16.dp))
          if (current == 0) {
              AppButton(stringResource(R.string.next), { step = 1 }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
          } else {
              AppButton(stringResource(R.string.onboarding_start), { permissions.launch(startPermissions()) }, Modifier.fillMaxWidth())
          }
        }
    }
}

@Composable
private fun Reason(icon: ImageVector, title: String, text: String) {
    val colors = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.text)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.textSecondary)
            }
        }
    }
}
