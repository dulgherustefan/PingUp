package ro.safetyplease.ui.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.compose.resources.stringResource
import ro.safetyplease.ui.AppViewModel
import ro.safetyplease.ui.Dest
import ro.safetyplease.ui.designsystem.AppTheme
import ro.safetyplease.ui.designsystem.GlassIconButton
import ro.safetyplease.ui.designsystem.GroupDivider
import ro.safetyplease.ui.designsystem.GroupRow
import ro.safetyplease.ui.designsystem.Gutter
import ro.safetyplease.ui.designsystem.InsetGroup
import ro.safetyplease.ui.designsystem.NavScreen
import ro.safetyplease.ui.designsystem.SectionTitle
import ro.safetyplease.ui.designsystem.Sym
import ro.safetyplease.ui.designsystem.body
import ro.safetyplease.ui.designsystem.footnote
import ro.safetyplease.ui.resources.Res
import ro.safetyplease.ui.resources.back
import ro.safetyplease.ui.resources.licenses_components
import ro.safetyplease.ui.resources.licenses_copyright
import ro.safetyplease.ui.resources.licenses_intro
import ro.safetyplease.ui.resources.licenses_source
import ro.safetyplease.ui.resources.licenses_version
import ro.safetyplease.ui.resources.me_licenses

/** The entry for Ping Up itself in licenses.json, with the GPL and CC BY texts. */
internal const val PING_UP_ID = "org.pingup:ping-up"

/** One thing the app ships and its licenses; [targets] are the Kotlin targets that use it (in the iPhone's list only). */
internal class Component(
    val id: String,
    val name: String,
    val version: String?,
    val description: String?,
    val website: String?,
    val licenses: List<License>,
    val targets: List<String>,
)

internal class License(val name: String, val text: String)

/** licenses.json, in the format the AboutLibraries plugin writes (`./gradlew exportLicenses`). */
internal fun parseComponents(json: String): List<Component> {
    val root = Json.parseToJsonElement(json).jsonObject
    val licenses = root["licenses"]?.jsonObject.orEmpty().mapValues { (key, value) ->
        val license = value.jsonObject
        License(license.text("name") ?: key, license.text("content").orEmpty())
    }
    return root["libraries"]?.jsonArray.orEmpty().map { element ->
        val library = element.jsonObject
        val id = library.text("uniqueId").orEmpty()
        Component(
            id = id,
            name = library.text("name") ?: id,
            version = library.text("artifactVersion"),
            description = library.text("description"),
            website = library.text("website"),
            licenses = library.texts("licenses").mapNotNull(licenses::get),
            targets = library.texts("targets"),
        )
    }
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.texts(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

/**
 * What this build ships, Ping Up first, then by name. The iPhone's list (from :ui) tags each library with the Kotlin
 * targets that use it, and only the shared ones ("metadata") are in the iPhone framework; the Android app's list (from
 * :app) and the entries added by hand in config/aboutlibraries have no targets and are all shipped.
 */
internal fun shipped(components: List<Component>): List<Component> =
    components
        .filter { it.targets.isEmpty() || "metadata" in it.targets }
        .sortedWith(compareBy({ it.id != PING_UP_ID }, { it.name.lowercase() }))

/** The license names of [component], for its row, in the list's order: Ping Up's GPL before its CC BY. */
internal fun licenseNames(component: Component): String = component.licenses.map { it.name }.distinct().joinToString(", ")

private val ListItem = Regex("""^\s*(\(?[0-9]{1,2}(\.[0-9]{1,2})*[.)]|\(?[a-zA-Z][.)]|\([ivx]+\)|[-*•])\s""")
private val Rule = Regex("""^[-=_*~]{3,}$""")
private val Spaces = Regex(""" {2,}""")

/**
 * A license text for a phone's width. The files wrap their lines at about 70 characters, which a narrow screen wraps
 * again into ragged halves: here lines join into their paragraph, and a blank line, a list item or a rule of dashes
 * starts a new one. Only the white space changes.
 */
internal fun reflow(text: String): String {
    val paragraphs = mutableListOf<StringBuilder>()
    var current: StringBuilder? = null
    for (raw in text.lines()) {
        val line = raw.trim().replace(Spaces, " ")
        when {
            line.isEmpty() -> current = null
            Rule.matches(line) -> {
                paragraphs += StringBuilder(line)
                current = null
            }
            current == null || ListItem.containsMatchIn(raw) -> current = StringBuilder(line).also { paragraphs += it }
            else -> current.append(' ').append(line)
        }
    }
    return paragraphs.joinToString("\n\n")
}

private var parsed: List<Component>? = null

/** licenses.json of this platform, parsed once off the main thread. */
@Composable
private fun rememberComponents(): List<Component>? {
    val components by produceState(parsed) {
        if (value == null) {
            value = withContext(Dispatchers.Default) { parseComponents(Res.readBytes("files/licenses.json").decodeToString()) }
                .also { parsed = it }
        }
    }
    return components
}

/** Ping Up's licence, where its code is, and everything it ships with its licence: the notices the licences ask for. */
@Composable
fun LicensesScreen(vm: AppViewModel) {
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val components = rememberComponents()
    NavScreen(
        title = stringResource(Res.string.me_licenses),
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding)) {
            Text(
                stringResource(Res.string.licenses_intro), style = MaterialTheme.typography.body, color = colors.label,
                modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
            )
            Text(
                stringResource(Res.string.licenses_source) + "\n" + stringResource(Res.string.licenses_copyright),
                style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 12.dp),
            )
            val shipped = components?.let(::shipped).orEmpty()
            val app = shipped.firstOrNull { it.id == PING_UP_ID }
            if (app != null) {
                InsetGroup(Modifier.padding(top = 20.dp)) {
                    GroupRow(app.name, subtitle = licenseNames(app), chevron = true, onClick = { vm.open(Dest.Component(app.id)) })
                }
            }
            val others = shipped.filter { it.id != PING_UP_ID }
            if (others.isNotEmpty()) {
                SectionTitle(stringResource(Res.string.licenses_components))
                InsetGroup(Modifier.padding(bottom = 24.dp)) {
                    others.forEachIndexed { i, component ->
                        if (i > 0) GroupDivider(start = Gutter)
                        val version = component.version?.let { " $it" }.orEmpty()
                        GroupRow(
                            component.name + version, subtitle = licenseNames(component), chevron = true,
                            onClick = { vm.open(Dest.Component(component.id)) },
                        )
                    }
                }
            }
        }
    }
}

/** One component: its version, what it is and the notices it asks for, where it lives, then each licence in full. */
@Composable
fun ComponentScreen(vm: AppViewModel, id: String) {
    val components = rememberComponents() ?: return
    val component = components.firstOrNull { it.id == id }
    if (component == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val colors = AppTheme.colors
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    NavScreen(
        title = component.name,
        background = colors.grouped,
        scrolled = scrolled,
        leading = { backdrop -> GlassIconButton(Sym.Back, stringResource(Res.string.back), { vm.back() }, backdrop) },
    ) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(padding).padding(bottom = 24.dp)) {
            // Ping Up's own words come in the app's language; the other components' notices are in English, as written
            val details = if (component.id == PING_UP_ID) {
                listOf(stringResource(Res.string.licenses_intro), stringResource(Res.string.licenses_source))
            } else {
                listOfNotNull(
                    component.version?.let { stringResource(Res.string.licenses_version, it) },
                    component.description?.takeIf { it.isNotBlank() },
                    component.website?.takeIf { it.isNotBlank() },
                )
            }
            for (detail in details) {
                Text(
                    detail, style = MaterialTheme.typography.body, color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = Gutter * 2, end = Gutter * 2, top = 8.dp),
                )
            }
            for (license in component.licenses.distinctBy { it.name to it.text }) {
                SectionTitle(license.name, color = colors.label)
                Text(
                    reflow(license.text),
                    style = MaterialTheme.typography.footnote, color = colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = Gutter * 2),
                )
            }
        }
    }
}
