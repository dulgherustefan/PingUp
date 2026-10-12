package ro.safetyplease.ui.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/** Whether the camera may scan; [blocked] when only the phone's settings can allow it now. [request] asks. */
@Immutable
class CameraAccess(val granted: Boolean, val blocked: Boolean, val request: () -> Unit)

/** The camera permission as scanning needs it, checked again whenever the app comes back to the front. */
@Composable
expect fun rememberCameraAccess(): CameraAccess

/** The back camera's picture filling [modifier]; [onCode] gets each QR code it reads, the same one many times a second. */
@Composable
expect fun QrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier)
