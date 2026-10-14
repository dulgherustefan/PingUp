buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.aboutlibraries) apply false
}

// After a dependency changes: rewrites the lists the open source licences screen shows, for Android and the iPhone
tasks.register("exportLicenses") {
    group = "documentation"
    description = "Writes the open source licences lists of the Android app and the iPhone app."
    dependsOn(":app:exportLibraryDefinitions", ":ui:exportLibraryDefinitions")
}
