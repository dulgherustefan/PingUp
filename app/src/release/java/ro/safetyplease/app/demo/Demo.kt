package ro.safetyplease.app.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.ui.AppViewModel

/** Demo mode only exists in debug builds; this is its empty shape. */
object Demo {
    const val AVAILABLE = false

    fun install(container: AppContainer) = Unit

    @Composable
    fun Screen(vm: AppViewModel) {
        LaunchedEffect(Unit) { vm.back() }
    }
}
