package ro.safetyplease.ui.ios

import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import kotlin.concurrent.Volatile

/**
 * The shared texts for code off the screens (notifications, the session's notices), in the language the app pinned at
 * launch. Compose reads that from the screen, which only the main thread may touch, so it's read there once and the
 * other threads use the copy: iOS ends an app whose language changes, so the copy holds.
 */
object IosTexts {
    @Volatile private var environment: ResourceEnvironment? = null

    /** On the main thread, before any other thread asks for a text. */
    fun capture() {
        environment = getSystemResourceEnvironment()
    }

    private fun environment(): ResourceEnvironment = environment ?: getSystemResourceEnvironment()

    suspend fun text(resource: StringResource): String = getString(environment(), resource)

    suspend fun text(resource: StringResource, vararg args: Any): String = getString(environment(), resource, *args)

    suspend fun plural(resource: PluralStringResource, quantity: Int, vararg args: Any): String =
        getPluralString(environment(), resource, quantity, *args)
}
