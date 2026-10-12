package ro.safetyplease.ui.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

@Composable
actual fun rememberCameraAccess(): CameraAccess {
    fun status() = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
    var current by remember { mutableStateOf(status()) }
    // allowed or refused in Settings while the app was away
    LifecycleResumeEffect(Unit) {
        current = status()
        onPauseOrDispose { }
    }
    return CameraAccess(
        granted = current == AVAuthorizationStatusAuthorized,
        // iOS asks once; after that only Settings can change the answer
        blocked = current == AVAuthorizationStatusDenied || current == AVAuthorizationStatusRestricted,
    ) {
        if (status() == AVAuthorizationStatusNotDetermined) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ -> dispatch_async(dispatch_get_main_queue()) { current = status() } }
        }
    }
}

@Composable
actual fun QrScanner(onCode: (String) -> Unit, modifier: Modifier) {
    val callback by rememberUpdatedState(onCode)
    val camera = remember { QrCamera { callback(it) } }
    DisposableEffect(camera) {
        camera.start()
        onDispose { camera.stop() }
    }
    UIKitView(factory = { camera.view }, modifier = modifier)
}

/**
 * The back camera reading QR codes with iOS's own detector. A phone without a camera, like the simulator, shows the
 * black view and reads nothing.
 */
@OptIn(ExperimentalForeignApi::class)
private class QrCamera(onCode: (String) -> Unit) {
    private val session = AVCaptureSession()

    // starting and stopping block, so they run off the main thread, one after the other
    private val queue = dispatch_queue_create("ro.safetyplease.camera", null)
    private val reader = CodeReader(onCode)
    val view = PreviewView(session)

    init {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
        val input = device?.let { AVCaptureDeviceInput.deviceInputWithDevice(it, error = null) }
        if (input != null && session.canAddInput(input)) {
            session.addInput(input)
            val output = AVCaptureMetadataOutput()
            if (session.canAddOutput(output)) {
                session.addOutput(output)
                output.setMetadataObjectsDelegate(reader, dispatch_get_main_queue())
                // only once the output is in the session does it know which codes it can read
                output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
            }
        }
    }

    fun start() = dispatch_async(queue) { session.startRunning() }

    fun stop() = dispatch_async(queue) { session.stopRunning() }
}

private class CodeReader(private val onCode: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        for (code in didOutputMetadataObjects) (code as? AVMetadataMachineReadableCodeObject)?.stringValue?.let(onCode)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PreviewView(session: AVCaptureSession) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val preview = AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }

    init {
        backgroundColor = UIColor.blackColor
        layer.addSublayer(preview)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        preview.frame = bounds
    }
}
