package ro.safetyplease.ui.me

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LicensesScreenTest {
    // the shape the AboutLibraries plugin writes; the iPhone's list tags each library with its Kotlin targets
    private val json = """
        {
          "libraries": [
            {"uniqueId": "com.example:zeta", "name": "zeta", "artifactVersion": "1.0", "licenses": ["Apache-2.0"], "targets": ["android", "metadata"]},
            {"uniqueId": "androidx.camera:camera-core", "name": "Camera Core", "licenses": ["Apache-2.0", "BSD-3-Clause"], "targets": ["android"]},
            {"uniqueId": "com.google:skia", "name": "Skia", "description": "Inside Skiko.", "licenses": ["skia-bsd", "gone"]},
            {"uniqueId": "org.pingup:ping-up", "name": "Ping Up", "licenses": ["pingup-gpl"]},
            {"uniqueId": "com.example:alpha", "name": "Alpha", "licenses": ["MIT", "Apache-2.0"], "targets": ["metadata"]}
          ],
          "licenses": {
            "Apache-2.0": {"name": "Apache License 2.0", "content": "Apache text"},
            "BSD-3-Clause": {"name": "BSD 3-Clause License", "content": "BSD text"},
            "MIT": {"name": "MIT License", "content": "MIT text"},
            "skia-bsd": {"name": "BSD 3-Clause License", "content": "Copyright (c) 2011 Google Inc."},
            "pingup-gpl": {"name": "GNU General Public License v3.0 or later", "content": "GPL text"}
          }
        }
    """.trimIndent()

    @Test
    fun readsEachLibraryWithItsLicenseTexts() {
        val skia = parseComponents(json).single { it.id == "com.google:skia" }
        assertEquals("Skia", skia.name)
        assertNull(skia.version)
        assertEquals("Inside Skiko.", skia.description)
        // a license the file doesn't define is left out rather than shown empty
        assertEquals(listOf("Copyright (c) 2011 Google Inc."), skia.licenses.map { it.text })
        assertEquals(emptyList(), skia.targets)
    }

    @Test
    fun theIphoneListKeepsOnlyWhatItsFrameworkLinks() {
        val shown = shipped(parseComponents(json)).map { it.id }
        // Ping Up first, then by name; Camera Core is only in the Android app
        assertEquals(listOf("org.pingup:ping-up", "com.example:alpha", "com.google:skia", "com.example:zeta"), shown)
    }

    @Test
    fun aRowNamesEachLicenseOnceInTheListsOrder() {
        val alpha = parseComponents(json).single { it.id == "com.example:alpha" }
        assertEquals("MIT License, Apache License 2.0", licenseNames(alpha))
        val skia = parseComponents(json).single { it.id == "com.google:skia" }
        assertEquals("BSD 3-Clause License", licenseNames(skia))
    }

    @Test
    fun aLicenseTextFlowsInParagraphsWithOnlyItsWhiteSpaceChanged() {
        val text = """
            Copyright (c) 2011 Google Inc.  All rights reserved.

            Redistribution and use in source and binary forms, with or
            without modification, are permitted provided that the following
            conditions are met:
              * Redistributions of source code must retain the above
                copyright notice.
              (b) You must give any other recipients a copy
                  of this License; and
            ----------------------------------------------------------------
            THIS SOFTWARE IS PROVIDED "AS IS", e.g. without
            any warranty.
        """.trimIndent()
        assertEquals(
            listOf(
                "Copyright (c) 2011 Google Inc. All rights reserved.",
                "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:",
                "* Redistributions of source code must retain the above copyright notice.",
                "(b) You must give any other recipients a copy of this License; and",
                "----------------------------------------------------------------",
                "THIS SOFTWARE IS PROVIDED \"AS IS\", e.g. without any warranty.",
            ).joinToString("\n\n"),
            reflow(text),
        )
        // nothing but the white space between words changes
        assertEquals(text.split(Regex("\\s+")).filter { it.isNotEmpty() }, reflow(text).split(Regex("\\s+")).filter { it.isNotEmpty() })
    }
}
