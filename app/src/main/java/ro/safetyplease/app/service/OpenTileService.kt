package ro.safetyplease.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import ro.safetyplease.app.MainActivity

/** A Quick Settings tile that only opens the app at [target]. Nothing is sent from the tile itself. */
abstract class OpenTileService(private val target: String, private val requestCode: Int) : TileService() {
    override fun onClick() {
        if (isLocked) unlockAndRun(::open) else open()
    }

    // the Intent overload is the only one before Android 14 and throws from 14 on, hence the version check
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun open() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            startActivityAndCollapse(PendingIntent.getActivity(this, requestCode, intent, flags))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
