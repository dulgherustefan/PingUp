package ro.safetyplease.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ro.safetyplease.app.R
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.caption1

/** The band over every screen while the app runs an exercise pack, so a drill is never taken for the real thing. */
@Composable
fun ExerciseBanner(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().background(AppTheme.colors.orange).statusBarsPadding().semantics { heading() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.exercise_banner), style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold,
            color = Color.Black, modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}
