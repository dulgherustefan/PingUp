package ro.safetyplease.ui

import platform.Foundation.NSArgumentDomain
import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults

/**
 * The language iOS picked for the app among its own: the first one the person prefers that the app has, otherwise
 * Romanian, its development language. The Android app follows the same rule.
 */
fun appLanguage(): String = NSBundle.mainBundle.preferredLocalizations.firstOrNull() as? String ?: "ro"

/**
 * Makes [language] the first preferred one for this process. The shared texts read it, plural rules included:
 * Romanian text read with German rules would say "8 de zone". The argument domain isn't saved, so the person's own
 * choice in Settings stays theirs.
 */
fun pinLanguage(language: String) {
    val defaults = NSUserDefaults.standardUserDefaults
    val arguments = defaults.volatileDomainForName(NSArgumentDomain)
    defaults.setVolatileDomain(arguments + ("AppleLanguages" to listOf(language)), NSArgumentDomain)
}
