package ro.safetyplease.app

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * The app speaks English when the phone does and Romanian otherwise. Resources and the default locales are pinned to
 * that one: plural rules come from the locale, Android's from the resources' and the shared texts' from the default
 * list's first, and Romanian text with German rules would read "2 de hop-uri".
 */
object AppLocale {
    val ROMANIAN: Locale = Locale.forLanguageTag("ro-RO")

    private val SUPPORTED = setOf("ro", "en")

    fun pick(preferred: List<Locale>): Locale = preferred.firstOrNull { it.language in SUPPORTED } ?: ROMANIAN

    /** The phone's languages with the app's first. */
    fun ordered(preferred: List<Locale>): List<Locale> = pick(preferred).let { listOf(it) + (preferred - it) }

    /**
     * Makes the app's language the default, first in the default list too: Compose reads it there, where Android
     * leaves the phone's own first. Returns it.
     */
    fun pin(preferred: List<Locale>): Locale {
        val locales = ordered(preferred)
        LocaleList.setDefault(LocaleList(*locales.toTypedArray()))
        return locales.first()
    }

    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        val system = config.locales
        config.setLocale(pin(List(system.size()) { system[it] }))
        return base.createConfigurationContext(config)
    }

    /** After any configuration change: Android has just set its default list again, the phone's language first. */
    fun repin(config: Configuration) {
        pin(List(config.locales.size()) { config.locales[it] })
    }

    /**
     * "ro" or "en", as the app speaks now: the default [pin] set last. The application's own context keeps the language
     * it started with, and the person may change the phone's while the app runs.
     */
    fun current(): String = pick(listOf(Locale.getDefault())).language
}
