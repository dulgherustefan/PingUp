package ro.safetyplease.app.ui.common

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens the share sheet for files in the app's export folder, the only one its file provider serves. [onShared] is
 * told once an app was picked; backing out tells nothing.
 */
fun shareFiles(context: Context, files: List<File>, onShared: IntentSender? = null) {
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.log", it) }
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null, onShared))
}

/** The export folder, emptied: each export replaces the files of the last one. */
fun freshExportDir(dir: File): File = dir.apply {
    deleteRecursively()
    mkdirs()
}
