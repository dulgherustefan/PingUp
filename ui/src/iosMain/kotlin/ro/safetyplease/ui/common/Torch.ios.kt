package ro.safetyplease.ui.common

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.hasTorch
import platform.AVFoundation.torchActive
import platform.AVFoundation.torchMode

/** The back camera's flash; lighting it needs no permission, only the device for a moment. */
@OptIn(ExperimentalForeignApi::class)
class IosTorch : Torch {
    private val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)?.takeIf { it.hasTorch }
    private val lit = MutableStateFlow(false)
    private var ours = false

    override val available = device != null

    override val on: StateFlow<Boolean> = lit

    override fun set(on: Boolean): Boolean {
        val device = device ?: return false
        if (!device.lockForConfiguration(null)) return false
        try {
            device.torchMode = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
        } finally {
            device.unlockForConfiguration()
        }
        ours = on
        lit.value = device.torchActive
        return true
    }

    // iOS has no callback for the flash; it's read again each time the banner shows
    override fun watch() {
        lit.value = device?.torchActive == true
    }

    override fun release() {
        if (ours) set(false)
    }
}
