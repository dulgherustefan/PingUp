package ro.safetyplease.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ro.safetyplease.app.App
import kotlin.concurrent.thread

/**
 * "Am ajuns" and "+15 / +30 min" in the check-in's notification. The check-in lives in the session, not in the mesh,
 * so the answer goes straight to it: it works with the mesh off and never starts a mesh the user switched off.
 */
class CheckinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val checkin = (context.applicationContext as App).container.checkin
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        if (minutes == 0) checkin.arrived() else checkin.extend(minutes)
        // the process may go as soon as the receiver returns, so the answer is written before that
        val pending = goAsync()
        thread {
            try {
                checkin.flush()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "ro.safetyplease.app.CHECKIN"
        private const val EXTRA_MINUTES = "minutes"

        /** [minutes] more, or 0 for "Am ajuns". */
        fun intent(context: Context, minutes: Int): Intent =
            Intent(context, CheckinReceiver::class.java).setAction(ACTION).putExtra(EXTRA_MINUTES, minutes)
    }
}
