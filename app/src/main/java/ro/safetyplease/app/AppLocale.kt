package ro.safetyplease.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Aplicatia are texte doar in romana. Android alege insa forma de plural dupa limba telefonului, nu dupa
 * limba textului: pe un telefon in engleza, "2" ar lua forma pentru 20+ si ar iesi "2 de hop-uri".
 * De aceea resursele sunt fixate pe romana, indiferent de limba sistemului.
 */
object AppLocale {
    val ROMANIAN: Locale = Locale.forLanguageTag("ro-RO")

    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(ROMANIAN)
        return base.createConfigurationContext(config)
    }
}
