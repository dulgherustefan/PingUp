package ro.safetyplease.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.ui.AppRoot
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.OpenTarget
import ro.safetyplease.ui.common.LockScreenCover
import ro.safetyplease.ui.designsystem.AppLogo
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.FullScreen

class MainActivity : ComponentActivity() {
    private val container get() = (application as App).container
    private val vm: AppViewModel by viewModels {
        viewModelFactory { initializer { AppViewModel(container, AndroidPhoneUi(application, container)) } }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    /** The screens' state, once the container is built; until then the app shows it is starting. */
    private var model by mutableStateOf<AppViewModel?>(null)

    /** Started by an alert that takes the screen: over the lock screen until the alert shows, or a moment if it doesn't. */
    private var alertLaunch by mutableStateOf(false)
    private var alertLaunchEnds: Job? = null

    /** Alerts on screen now that may show over the lock screen ([AndroidPhoneUi.ShowOverLockScreen]). */
    private var alertsShown by mutableIntStateOf(0)

    /** The lock screen is up, behind an alert or not; read again each time the activity comes forward. */
    var keyguardLocked by mutableStateOf(false)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recreated under an alert (a rotation over the lock screen), it stays there until the alert shows again
        alertLaunch = savedInstanceState?.getBoolean(STATE_ALERT) ?: intent.getBooleanExtra(EXTRA_ALERT, false)
        showOverLockScreen()
        // follow the system theme; system bar icons flip with it
        enableEdgeToEdge(
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            AppTheme {
                // until the alert itself is up, nothing of the app shows or answers over the lock screen
                val covered = alertLaunch && alertsShown == 0 && keyguardLocked
                Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)) {
                    when (val vm = model) {
                        null -> Starting()
                        else -> AppRoot(vm, onStartMesh = ::startMesh) { Demo.Screen(vm) }
                    }
                }
                if (covered) LockScreenCover()
            }
        }
        whenReady {
            Demo.install(container)
            // on recreation (rotation, theme, font size) the launch intent was already handled
            if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN)?.let(vm::openTarget)
            model = vm
            if (alertLaunch) endAlertLaunchSoon()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ALERT, false)) {
            alertLaunch = true
            showOverLockScreen()
        }
        whenReady {
            intent.getStringExtra(EXTRA_OPEN)?.let(vm::openTarget)
            if (alertLaunch) endAlertLaunchSoon()
        }
    }

    override fun onStart() {
        super.onStart()
        whenReady {
            // a stop that came while the container was being built already ended this start
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@whenReady
            if (vm.settings.value.onboarded) startMesh()
            container.location.start()
        }
    }

    override fun onResume() {
        super.onResume()
        keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    override fun onStop() {
        if ((application as App).containerReady.value) container.location.stop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ALERT, alertLaunch || alertsShown > 0)
    }

    /** An alert that may show over the lock screen came on screen ([shown]) or left it. */
    fun alertShown(shown: Boolean) {
        alertsShown = (alertsShown + if (shown) 1 else -1).coerceAtLeast(0)
        if (shown) alertLaunch = false
        showOverLockScreen()
    }

    // an alert answered or gone before the screen came up leaves the app behind the lock screen again
    private fun endAlertLaunchSoon() {
        alertLaunchEnds?.cancel()
        alertLaunchEnds = lifecycleScope.launch {
            delay(ALERT_LAUNCH_MS)
            alertLaunch = false
            showOverLockScreen()
        }
    }

    /** Over the lock screen, waking it, only while an alert holds it: nothing else of the app shows there. */
    private fun showOverLockScreen() {
        val on = alertLaunch || alertsShown > 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(on)
            setTurnScreenOn(on)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (on) window.addFlags(flags) else window.clearFlags(flags)
        }
        keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }

    /** Runs [block] on the main thread now if the container is built, otherwise as soon as it is. */
    private fun whenReady(block: () -> Unit) {
        lifecycleScope.launch {
            (application as App).containerReady.first { it }
            block()
        }
    }

    private fun startMesh() = vm.phone.startMesh()

    companion object {
        // res/xml/shortcuts.xml repeats EXTRA_OPEN and OPEN_REPORT
        const val EXTRA_OPEN = "open"

        /** The intent comes from the full-screen part of an alert that may show over the lock screen. */
        const val EXTRA_ALERT = "alert"
        const val OPEN_HOME = OpenTarget.HOME
        const val OPEN_REPORT = OpenTarget.REPORT
        const val OPEN_DISCREET = OpenTarget.DISCREET
        const val OPEN_REPORT_PREFIX = OpenTarget.REPORT_PREFIX
        const val OPEN_CHAT_PREFIX = OpenTarget.CHAT_PREFIX
        const val OPEN_SHARING = OpenTarget.SHARING

        /** How long an alert's launch keeps the app over the lock screen when no alert shows. */
        private const val ALERT_LAUNCH_MS = 3_000L
        private const val STATE_ALERT = "alert"
    }
}

/** The first frame while the session loads: the app's logo on its background, as the launcher icon leaves it. */
@Composable
private fun Starting() {
    FullScreen {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppLogo(96.dp) }
    }
}
