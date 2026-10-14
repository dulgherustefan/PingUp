package ro.safetyplease.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import ro.safetyplease.app.App
import kotlin.concurrent.thread

/**
 * "Sunt OK" in the "Ești OK?" notification. It works on the lock screen: answering that you are fine is the one thing
 * the notification lets anyone do without unlocking; the incidents and the app still need the phone unlocked.
 */
class ManDownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // the alarm stops at once; the session may still be loading, so it hears the answer off the main thread
        NotificationManagerCompat.from(context).cancel(Notifier.MAN_DOWN_ID)
        val pending = goAsync()
        thread(name = "mandown") {
            try {
                (context.applicationContext as App).container.staffChain.answerManDown()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION = "ro.safetyplease.app.MAN_DOWN_OK"

        fun intent(context: Context): Intent = Intent(context, ManDownReceiver::class.java).setAction(ACTION)
    }
}
