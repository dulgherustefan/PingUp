package ro.safetyplease.ui.designsystem

import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

actual val CanBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
actual fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

actual val EdgeBounce = false
