package ro.safetyplease.app.platform.torch

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ro.safetyplease.ui.common.Torch

/**
 * The camera flash as a torch, so a team can spot the reporter. CameraManager switches it without opening the camera,
 * so it needs no permission; it fails while another app holds the camera. [release] puts out only what it lit.
 */
class CameraTorch(context: Context) : Torch {
    private val manager: CameraManager? = context.getSystemService(CameraManager::class.java)
    private val cameraId: String? = manager?.let { m ->
        runCatching { m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
            .getOrNull()
    }
    private val _on = MutableStateFlow(false)

    /** Lit, by this app or another: the system tells every app that watches. */
    override val on: StateFlow<Boolean> = _on
    private var lit = false

    private val callback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id != cameraId) return
            _on.value = enabled
            if (!enabled) lit = false
        }

        override fun onTorchModeUnavailable(id: String) {
            if (id != cameraId) return
            _on.value = false
            lit = false
        }
    }

    override val available: Boolean get() = cameraId != null

    override fun watch() {
        if (cameraId != null) runCatching { manager?.registerTorchCallback(callback, Handler(Looper.getMainLooper())) }
    }

    /** False if the flash didn't switch. */
    override fun set(on: Boolean): Boolean {
        val id = cameraId ?: return false
        val manager = manager ?: return false
        val switched = runCatching { manager.setTorchMode(id, on) }.isSuccess
        if (switched) {
            lit = on
            _on.value = on
        }
        return switched
    }

    override fun release() {
        if (lit) set(false)
        runCatching { manager?.unregisterTorchCallback(callback) }
    }
}
