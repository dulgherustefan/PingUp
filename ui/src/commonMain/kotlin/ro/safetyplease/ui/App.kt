package ro.safetyplease.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.designsystem.AppButton
import ro.safetyplease.ui.designsystem.AppLogo
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.designsystem.screenBackground
import ro.safetyplease.ui.designsystem.title1
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.onboarding_intro
import ro.safetyplease.ui.resources.onboarding_start
import ro.safetyplease.ui.resources.onboarding_welcome

/** The first screen, until the app's own screens move here: says what Ping Up is and which phone this is. */
@Composable
fun App(nodeId: String) {
    AppTheme {
        Column(
            Modifier.fillMaxSize().screenBackground(AppTheme.colors.background).safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            AppLogo(120.dp)
            Text(stringResource(Res.string.onboarding_welcome), style = MaterialTheme.typography.title1, textAlign = TextAlign.Center)
            Text(
                stringResource(Res.string.onboarding_intro), style = MaterialTheme.typography.bodyLarge,
                color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.Center,
            )
            Text(nodeId, style = MaterialTheme.typography.footnote, color = AppTheme.colors.tertiaryLabel)
            Spacer(Modifier.height(8.dp))
            AppButton(stringResource(Res.string.onboarding_start), onClick = {}, modifier = Modifier.fillMaxWidth())
        }
    }
}
