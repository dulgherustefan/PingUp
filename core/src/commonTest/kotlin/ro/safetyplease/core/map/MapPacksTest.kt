package ro.safetyplease.core.map

import kotlinx.serialization.json.Json
import ro.safetyplease.core.event.EventMapRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A pack's manifest and an event pack's map reference go through JSON and back unchanged, bbox as [W, S, E, N]. */
class MapPacksTest {
    private val hash = "b597dbfbb97e216a718625b85cd1b6112099a868352d1fe7e2c01d2a113e80c4"

    @Test
    fun manifestRoundTrip() {
        val manifest = PackManifest(
            id = "constanta",
            kind = PackKind.AREA,
            buildDate = "20261014",
            file = "constanta.pmtiles",
            sha256 = hash,
            bytes = 48_211_337L,
            bbox = listOf(28.25, 43.75, 29.5, 44.25),
            minzoom = 7,
            maxzoom = 15,
            attribution = "© OpenStreetMap contributors",
            source = "https://maps.example.org/planet.pmtiles",
            etag = "\"5f3a-1c\"",
            label = "Constanța",
        )
        val text = Json.encodeToString(PackManifest.serializer(), manifest)
        assertTrue("\"kind\":\"AREA\"" in text, text)
        assertTrue("\"bbox\":[28.25,43.75,29.5,44.25]" in text, text)
        assertEquals(manifest, Json.decodeFromString(PackManifest.serializer(), text))
    }

    @Test
    fun manifestOptionalFieldsDefault() {
        val text = """{"id":"costinesti","kind":"EVENT","buildDate":"20261013","file":"costinesti.pmtiles",""" +
            """"sha256":"$hash","bytes":1114474,"bbox":[28.59,43.92,28.675,44.01],"maxzoom":15}"""
        val manifest = Json.decodeFromString(PackManifest.serializer(), text)
        assertEquals(PackKind.EVENT, manifest.kind)
        assertEquals(listOf(28.59, 43.92, 28.675, 44.01), manifest.bbox)
        assertEquals(0, manifest.minzoom)
        assertEquals("", manifest.attribution)
        assertNull(manifest.source)
        assertNull(manifest.etag)
        assertEquals("", manifest.label)
        assertEquals(manifest, Json.decodeFromString(PackManifest.serializer(), Json.encodeToString(PackManifest.serializer(), manifest)))
    }

    @Test
    fun eventMapRoundTrip() {
        val text = """{"url":"https://maps.example.org/fest-2027.pmtiles","sha256":"$hash","bytes":1114474,""" +
            """"bbox":[28.59,43.92,28.675,44.01],"minzoom":0,"maxzoom":15,"buildDate":"20261013"}"""
        val ref = Json.decodeFromString(EventMapRef.serializer(), text)
        val expected = EventMapRef(
            "https://maps.example.org/fest-2027.pmtiles", hash, 1_114_474L, listOf(28.59, 43.92, 28.675, 44.01), 0, 15, "20261013",
        )
        assertEquals(expected, ref)
        assertEquals(ref, Json.decodeFromString(EventMapRef.serializer(), Json.encodeToString(EventMapRef.serializer(), ref)))
    }
}
