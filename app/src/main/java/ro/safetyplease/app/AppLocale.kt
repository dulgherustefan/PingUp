package ro.safetyplease.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Strings are Romanian only, but Android picks plural rules from the system locale: on an English phone
 * "2" would get the 20+ form ("2 de hop-uri"). So resources are pinned to Romanian.
 */
object AppLocale {
    val ROMANIAN: Locale = Locale.forLanguageTag("ro-RO")

    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(ROMANIAN)
        return base.createConfigurationContext(config)
    }
}
