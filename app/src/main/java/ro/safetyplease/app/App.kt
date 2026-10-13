package ro.safetyplease.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

class App : Application() {
    /** Built on a thread of its own as the process starts; a caller that needs it sooner waits for it. */
    val container: AppContainer by lazy { AppContainer(this).also { ready.value = true } }

    private val ready = MutableStateFlow(false)

    /** True once [container] is built: until then the screen shows the app is starting. */
    val containerReady: StateFlow<Boolean> = ready
    private var started = 0

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLocale.repin(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        // the session reads its keys, stores and event pack from disk: never on the main thread, which draws meanwhile
        thread(name = "session") {
            container
            ContextCompat.getMainExecutor(this).execute(::showForeground)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                showForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                started--
                showForeground()
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** Main thread only; before the container is built it is told once, as soon as it is. */
    private fun showForeground() {
        if (ready.value) container.foreground.value = started > 0
    }
}
