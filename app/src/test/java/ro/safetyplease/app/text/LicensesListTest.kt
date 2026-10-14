package ro.safetyplease.app.text

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The open source licences screen reads these lists (`./gradlew exportLicenses` writes them): each thing the apps ship
 * must be in its platform's list, with the full text of each of its licences, as those licences ask.
 */
class LicensesListTest {
    private class Listed(val id: String, val licenses: List<String>, val targets: List<String>)

    private fun read(platform: String): Pair<List<Listed>, JsonObject> {
        val root = Json.parseToJsonElement(File("../ui/src/$platform/composeResources/files/licenses.json").readText()).jsonObject
        val libraries = root.getValue("libraries").jsonArray.map { element ->
            val library = element.jsonObject
            Listed(
                library.getValue("uniqueId").jsonPrimitive.content,
                library["licenses"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                library["targets"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }
        return libraries to root.getValue("licenses").jsonObject
    }

    // the screen's rule: the iPhone's list tags libraries with Kotlin targets, and only the shared ones are linked
    private fun shipped(libraries: List<Listed>) = libraries.filter { it.targets.isEmpty() || "metadata" in it.targets }

    // ours, and what Gradle can't see: fonts, icons, the map's data and the native code inside libraries
    private val both = listOf(
        "org.pingup:ping-up", "rsms:inter", "phosphor-icons:core", "notofonts:noto-sans", "tangrams:icons",
        "org.openstreetmap:data", "org.libsodium:libsodium", "com.squareup.okio:okio", "io.github.g0dkar:qrcode-kotlin",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-serialization-json",
    )

    @Test
    fun everyLibraryHasTheTextOfEachOfItsLicences() {
        for (platform in listOf("androidMain", "iosMain")) {
            val (libraries, licenses) = read(platform)
            assertTrue(platform, libraries.isNotEmpty())
            for (library in libraries) {
                assertTrue("$platform: ${library.id} has no licence", library.licenses.isNotEmpty())
                for (key in library.licenses) {
                    val text = licenses[key]?.jsonObject?.get("content")?.jsonPrimitive?.content.orEmpty()
                    assertTrue("$platform: ${library.id} lacks the text of $key", text.isNotBlank())
                }
            }
        }
    }

    @Test
    fun theAndroidListHasWhatTheAppShips() {
        val ids = shipped(read("androidMain").first).map { it.id }.toSet()
        val android = both + listOf(
            "org.sourceware:libffi", "com.goterl:lazysodium-android", "net.java.dev.jna:jna", "org.maplibre.gl:android-sdk-opengl",
            "com.google.zxing:core", "androidx.camera:camera-core",
        )
        assertEquals(emptyList<String>(), android.filter { it !in ids })
    }

    @Test
    fun theIphoneListHasWhatItsFrameworkLinksAndNothingOfAndroids() {
        val ids = shipped(read("iosMain").first).map { it.id }.toSet()
        val iphone = both + listOf(
            "org.jetbrains.skiko:skiko", "com.google:skia", "org.unicode:icu", "org.harfbuzz:harfbuzz", "org.libexpat:expat",
            "org.webmproject:libwebp", "net.zlib:zlib", "org.libpng:libpng", "org.libjpeg-turbo:libjpeg-turbo", "com.google:piex",
            "com.adobe:dng-sdk", "org.threeten:threetenbp",
        )
        assertEquals(emptyList<String>(), iphone.filter { it !in ids })
        assertEquals(emptyList<String>(), listOf("androidx.camera:camera-core", "org.maplibre.gl:android-sdk-opengl", "com.goterl:lazysodium-android").filter { it in ids })
    }
}
