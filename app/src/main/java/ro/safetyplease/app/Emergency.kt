package ro.safetyplease.app

import android.content.Context
import android.content.Intent
import android.net.Uri

/** The app never calls by itself: it opens the dialer with 112 filled in and the person places the call. */
object Emergency {
    private const val NUMBER = "112"

    fun dialIntent(): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", NUMBER, null))

    fun dial(context: Context) {
        // a tablet may have no dialer
        runCatching { context.startActivity(dialIntent()) }
    }
}
