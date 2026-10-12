package ro.safetyplease.app.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R
import ro.safetyplease.app.ui.AppViewModel
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.footnote

/** What each privacy section is about, in reading order; the same text is in PRIVACY.md. */
private val sections = listOf(
    R.string.privacy_kept_title to R.string.privacy_kept_text,
    R.string.privacy_sent_title to R.string.privacy_sent_text,
    R.string.privacy_location_title to R.string.privacy_location_text,
    R.string.privacy_camera_title to R.string.privacy_camera_text,
    R.string.privacy_internet_title to R.string.privacy_internet_text,
    R.string.privacy_metrics_title to R.string.privacy_metrics_text,
    R.string.privacy_staff_title to R.string.privacy_staff_text,
    R.string.privacy_never_title to R.string.privacy_never_text,
    R.string.privacy_delete_title to R.string.privacy_delete_text,
    R.string.privacy_contact_title to R.string.privacy_contact_text,
)

/** The privacy policy, in the app: it has to work without internet, so it isn't a link to the site. */
@Composable
fun PrivacyScreen(vm: AppViewModel) {
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = stringResource(R.string.privacy_title),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(R.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding)) {
            Text(
                stringResource(R.string.privacy_intro), style = MaterialTheme.typography.body, color = colors.label,
                modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
            )
            for ((title, text) in sections) {
                SectionTitle(stringResource(title), color = colors.label)
                Text(
                    stringResource(text), style = MaterialTheme.typography.body, color = colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = Gutter * 2),
                )
            }
            Text(
                stringResource(R.string.privacy_updated), style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 24.dp),
            )
        }
    }
}
