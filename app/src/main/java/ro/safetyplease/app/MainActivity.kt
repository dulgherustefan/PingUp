package ro.safetyplease.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.ui.AppRoot
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.OpenTarget
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // follow the system theme; system bar icons flip with it
        enableEdgeToEdge(
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            AppTheme {
                when (val vm = model) {
                    null -> Starting()
                    else -> AppRoot(vm, onStartMesh = ::startMesh) { Demo.Screen(vm) }
                }
            }
        }
        whenReady {
            Demo.install(container)
            // on recreation (rotation, theme, font size) the launch intent was already handled
            if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN)?.let(vm::openTarget)
            model = vm
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        whenReady { intent.getStringExtra(EXTRA_OPEN)?.let(vm::openTarget) }
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

    override fun onStop() {
        if ((application as App).containerReady.value) container.location.stop()
        super.onStop()
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
        const val OPEN_HOME = OpenTarget.HOME
        const val OPEN_INCIDENTS = OpenTarget.INCIDENTS
        const val OPEN_REPORT = OpenTarget.REPORT
        const val OPEN_DISCREET = OpenTarget.DISCREET
        const val OPEN_REPORT_PREFIX = OpenTarget.REPORT_PREFIX
        const val OPEN_CHAT_PREFIX = OpenTarget.CHAT_PREFIX
        const val OPEN_SHARING = OpenTarget.SHARING
    }
}

/** The first frame while the session loads: the app's logo on its background, as the launcher icon leaves it. */
@Composable
private fun Starting() {
    FullScreen {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppLogo(96.dp) }
    }
}
