package ro.safetyplease.app.platform.share

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Hands exported files to the share sheet; only the export folder in the app's cache is reachable through it. */
class LogFiles : FileProvider()

/** The share sheet calls it once the person picked an app; backing out calls nothing. */
class ExportShared : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ShareSheet.shared.getAndSet(null)?.invoke()
    }
}

object ShareSheet {
    /** What the latest share sheet does once an app was picked. */
    internal val shared = AtomicReference<(() -> Unit)?>(null)

    /** The share sheet for [files] in the export folder, the only one [LogFiles] serves; [onShared] once an app was picked. */
    fun intent(context: Context, files: List<File>, onShared: (() -> Unit)? = null): Intent {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.log", it) }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        shared.set(onShared)
        val picked = onShared?.let {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getBroadcast(context, 0, Intent(context, ExportShared::class.java), flags).intentSender
        }
        return Intent.createChooser(send, null, picked)
    }
}
