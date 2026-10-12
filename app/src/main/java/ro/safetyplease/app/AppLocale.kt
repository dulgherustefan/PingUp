package ro.safetyplease.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app speaks English when the phone does and Romanian otherwise. Resources are pinned to that one locale:
 * Android picks plural rules from the locale, and Romanian text with German rules would read "2 de hop-uri".
 */
object AppLocale {
    val ROMANIAN: Locale = Locale.forLanguageTag("ro-RO")

    private val SUPPORTED = setOf("ro", "en")

    fun pick(preferred: List<Locale>): Locale = preferred.firstOrNull { it.language in SUPPORTED } ?: ROMANIAN

    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        val system = config.locales
        config.setLocale(pick(List(system.size()) { system[it] }))
        return base.createConfigurationContext(config)
    }

    /** "ro" or "en", for a context [wrap] has pinned. */
    fun language(context: Context): String = context.resources.configuration.locales[0].language
}
