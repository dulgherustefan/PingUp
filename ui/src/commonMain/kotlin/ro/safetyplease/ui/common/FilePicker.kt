package ro.safetyplease.ui.common

import androidx.compose.runtime.Composable
import okio.Source

/** A file the person picked; [open] reads it, off the main thread. */
fun interface PickedFile {
    fun open(): Source
}

/**
 * The phone's own file picker for files of [types], MIME types with the usual star for any, one or [multiple]. The
 * returned function opens it; [onPicked] gets the files, and isn't called when the person backs out.
 */
@Composable
expect fun rememberFilePicker(types: List<String>, multiple: Boolean = false, onPicked: (List<PickedFile>) -> Unit): () -> Unit
