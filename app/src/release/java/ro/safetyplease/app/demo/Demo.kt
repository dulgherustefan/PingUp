package ro.safetyplease.app.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ro.safetyplease.app.AppContainer
import ro.safetyplease.app.ui.AppViewModel

/** Modul demo exista doar in build-ul debug; aici e doar forma lui goala. */
object Demo {
    const val AVAILABLE = false

    fun install(container: AppContainer) = Unit

    @Composable
    fun Screen(vm: AppViewModel) {
        LaunchedEffect(Unit) { vm.back() }
    }
}
