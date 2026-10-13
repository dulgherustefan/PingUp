package ro.safetyplease.ui.people

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import ro.safetyplease.ui.deniedForGood
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
actual fun rememberCameraAccess(): CameraAccess {
    val context = LocalContext.current
    val activity = LocalActivity.current
    fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(allowed()) }
    // denied for good, the camera can only be allowed from settings; checked again on return
    var blocked by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = allowed()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        blocked = !it && deniedForGood(activity, listOf(Manifest.permission.CAMERA))
    }
    return CameraAccess(granted, blocked) { launcher.launch(Manifest.permission.CAMERA) }
}

private class QrAnalyzer(private val onResult: (String) -> Unit) : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val data = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
            QrDecoder.decode(data, plane.rowStride, image.width, image.height)?.let(onResult)
        } catch (_: RuntimeException) {
        } finally {
            image.close()
        }
    }
}

@Composable
actual fun QrScanner(onCode: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onCode)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val disposed = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            // no blocking get(): if CameraX isn't ready yet, the listener below sees we've left and binds nothing
            val future = ProcessCameraProvider.getInstance(context)
            if (future.isDone) runCatching { future.get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                if (disposed.get() || owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return@addListener
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val main = ContextCompat.getMainExecutor(ctx)
                    analysis.setAnalyzer(executor, QrAnalyzer { text -> main.execute { callback(text) } })
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}
