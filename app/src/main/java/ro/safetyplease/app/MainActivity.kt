package ro.safetyplease.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import ro.safetyplease.app.demo.Demo
import ro.safetyplease.app.service.MeshService
import ro.safetyplease.app.ui.AppRoot
import ro.safetyplease.app.ui.AppTheme
import ro.safetyplease.app.ui.AppViewModel

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        Demo.install(vm.c)
        intent?.getStringExtra(EXTRA_OPEN)?.let(vm::openTarget)
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
        vm.c.location.start()
    }

    override fun onStop() {
        vm.c.location.stop()
        super.onStop()
    }

    /** Serviciul porneste doar cu permisiunile acordate: pe Android 14+ altfel nu poate intra in prim-plan. */
    private fun startMesh() {
        if (!vm.c.radio.hasPermissions()) return
        if (MeshService.running) vm.retryRadio() else MeshService.start(this)
        vm.c.location.start()
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_HOME = "home"
        const val OPEN_INCIDENTS = "incidents"
        const val OPEN_REPORT = "report"
        const val OPEN_CHAT_PREFIX = "chat:"
    }
}
