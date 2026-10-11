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

    /** The first of the phone's languages the app has strings for, in the phone's order; Romanian when none is. */
    fun pick(preferred: List<Locale>): Locale = preferred.firstOrNull { it.language in SUPPORTED } ?: ROMANIAN

    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        val system = config.locales
        config.setLocale(pick(List(system.size()) { system[it] }))
        return base.createConfigurationContext(config)
    }

    /** Language code of [context]'s strings, "ro" or "en", once [wrap] has pinned it. */
    fun language(context: Context): String = context.resources.configuration.locales[0].language
}
