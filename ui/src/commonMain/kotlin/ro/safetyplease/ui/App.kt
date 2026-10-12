package ro.safetyplease.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.onboarding_intro
import ro.safetyplease.ui.resources.onboarding_welcome

/** The first screen, until the app's own screens move here: says what Ping Up is and which phone this is. */
@Composable
fun App(nodeId: String) {
    Column(
        Modifier.fillMaxSize().background(Forest).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BasicText(stringResource(Res.string.onboarding_welcome), style = TextStyle(Sage, 28.sp, FontWeight.SemiBold))
        BasicText(stringResource(Res.string.onboarding_intro), style = TextStyle(Sage, 17.sp))
        BasicText(nodeId, style = TextStyle(Sage.copy(alpha = 0.7f), 15.sp))
    }
}

private val Forest = Color(0xFF2B5E45)
private val Sage = Color(0xFFD3D8B2)
