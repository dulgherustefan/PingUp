package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject

@Composable
actual fun rememberFilePicker(types: List<String>, multiple: Boolean, onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val callback by rememberUpdatedState(onPicked)
    // the picker holds its delegate weakly, so it lives here
    val delegate = remember { PickerDelegate { callback(it) } }
    return {
        // as a copy in the app's own folder: no access to ask for, nothing to give back
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = types.map(::contentType), asCopy = true)
        picker.allowsMultipleSelection = multiple
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
    }
}

private class PickerDelegate(private val onPicked: (List<PickedFile>) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val files = didPickDocumentsAtURLs.mapNotNull { (it as? NSURL)?.path }.map { path -> PickedFile { FileSystem.SYSTEM.source(path.toPath()) } }
        if (files.isNotEmpty()) onPicked(files)
    }
}

private fun contentType(mime: String): UTType = if (mime == "*/*") UTTypeItem else UTType.typeWithMIMEType(mime) ?: UTTypeData
