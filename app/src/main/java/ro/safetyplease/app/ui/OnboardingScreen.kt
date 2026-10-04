package ro.safetyplease.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R

private const val MAX_NICKNAME = 20

@Composable
fun OnboardingScreen(vm: AppViewModel, onStartMesh: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var nickname by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { step = 2 }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.onboarding_step, step + 1, 3),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            )
            when (step) {
                0 -> {
                    Text(stringResource(R.string.onboarding_welcome, stringResource(R.string.app_name)), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.onboarding_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(MAX_NICKNAME) },
                        label = { Text(stringResource(R.string.nickname)) },
                        supportingText = { Text(stringResource(R.string.nickname_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { step = 1 }, enabled = nickname.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.next))
                    }
                }
                1 -> {
                    Text(stringResource(R.string.onboarding_permissions), style = MaterialTheme.typography.headlineMedium)
                    InfoCard(AppIcons.Bluetooth, stringResource(R.string.perm_bluetooth_title), stringResource(R.string.perm_bluetooth_text))
                    InfoCard(AppIcons.Warning, stringResource(R.string.perm_notifications_title), stringResource(R.string.perm_notifications_text))
                    InfoCard(AppIcons.Place, stringResource(R.string.perm_location_title), stringResource(R.string.perm_location_text))
                    Button(onClick = { permissions.launch(meshPermissions()) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.grant_permissions))
                    }
                }
                else -> {
                    Text(stringResource(R.string.onboarding_ready), style = MaterialTheme.typography.headlineMedium)
                    if (!vm.c.radio.hasPermissions()) {
                        InfoCard(AppIcons.Warning, stringResource(R.string.perm_missing_title), stringResource(R.string.perm_missing_text), Palette.Urgent)
                    }
                    if (canAdvertise(context) == false) {
                        InfoCard(AppIcons.Bluetooth, stringResource(R.string.leaf_title), stringResource(R.string.leaf_text), Palette.Medium)
                    }
                    InfoCard(AppIcons.Shield, stringResource(R.string.foreground_title), stringResource(R.string.foreground_text))
                    InfoCard(AppIcons.Clock, stringResource(R.string.battery_title), stringResource(R.string.battery_text))
                    OutlinedButton(
                        onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.battery_open)) }
                    Button(
                        onClick = {
                            vm.finishOnboarding(nickname)
                            onStartMesh()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.done)) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun InfoCard(icon: ImageVector, title: String, text: String, accent: Color = MaterialTheme.colorScheme.secondary) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
