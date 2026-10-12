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
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.ui.AppRoot
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.OpenTarget
import ro.safetyplease.ui.designsystem.AppTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as App).container
    private val vm: AppViewModel by viewModels {
        viewModelFactory { initializer { AppViewModel(container, AndroidPhoneUi(application, container)) } }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(AppLocale.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // follow the system theme; system bar icons flip with it
        enableEdgeToEdge(
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        Demo.install(container)
        // on recreation (rotation, theme, font size) the launch intent was already handled
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_OPEN)?.let(vm::openTarget)
        setContent {
            AppTheme { AppRoot(vm, onStartMesh = ::startMesh) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let(vm::openTarget)
    }

    override fun onStart() {
        super.onStart()
        if (vm.settings.value.onboarded) startMesh()
        container.location.start()
    }

    override fun onStop() {
        container.location.stop()
        super.onStop()
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
    }
}
