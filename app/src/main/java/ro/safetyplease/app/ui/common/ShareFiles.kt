package ro.safetyplease.app.ui.common

import android.content.Context
import ro.safetyplease.app.platform.share.ShareSheet
import java.io.File

/**
 * Opens the share sheet for files in the app's export folder, the only one its file provider serves. [onShared] is
 * told once an app was picked; backing out tells nothing.
 */
fun shareFiles(context: Context, files: List<File>, onShared: (() -> Unit)? = null) {
    context.startActivity(ShareSheet.intent(context, files, onShared))
}

/** The export folder, emptied: each export replaces the files of the last one. */
fun freshExportDir(dir: File): File = dir.apply {
    deleteRecursively()
    mkdirs()
}
