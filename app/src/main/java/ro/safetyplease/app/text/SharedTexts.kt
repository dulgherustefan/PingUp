package ro.safetyplease.app.text

import android.content.Context
import android.content.res.Resources
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringArrayResource
import org.jetbrains.compose.resources.StringResource
import ro.safetyplease.app.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/*
 * A shared text (:ui's composeResources) read by Android code outside Compose, such as notifications. The build copies
 * the same strings.xml into this app's resources, so the key names one of them, whose id is looked up once.
 */

fun Context.text(res: StringResource, vararg args: Any): String = resources.text(res, *args)

fun Context.quantityText(res: PluralStringResource, count: Int, vararg args: Any): String = resources.quantityText(res, count, *args)

fun Resources.text(res: StringResource, vararg args: Any): String = getString(sharedId(res.key, "string"), *args)

fun Resources.quantityText(res: PluralStringResource, count: Int, vararg args: Any): String =
    getQuantityString(sharedId(res.key, "plurals"), count, *args)

fun Resources.textArray(res: StringArrayResource): Array<String> = getStringArray(sharedId(res.key, "array"))

private val ids = ConcurrentHashMap<String, Int>()

private fun Resources.sharedId(key: String, type: String): Int = ids.getOrPut("$type/$key") {
    @Suppress("DiscouragedApi") // by name on purpose: the key is all the shared resource knows
    getIdentifier(key, type, BuildConfig.APPLICATION_ID).also { check(it != 0) { "no $type $key in the app's resources" } }
}
