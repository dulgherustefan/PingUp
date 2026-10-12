import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The screens, shared: Compose Multiplatform for the Android app and the iPhone app. Moved here from :app one by one;
// whatever needs the phone itself (Bluetooth, camera, notifications) stays behind interfaces in :core or the apps.
plugins {
    // the Kotlin plugin is already on the classpath from the root build.gradle.kts, so no version here
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "ro.safetyplease.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        // off by default in AGP 9's library plugin; without it the shared resources (fonts, files) never reach the APK
        androidResources { enable = true }
        // the shared tests run on the JVM too, as `./gradlew test` and CI run them
        withHostTest {}
    }
    // the iPhone app links one static framework with the screens, :core and libsodium in it
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "PingUpUi"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.compose.mp.runtime)
            implementation(libs.compose.mp.foundation)
            implementation(libs.compose.mp.ui)
            implementation(libs.compose.mp.resources)
            implementation(libs.compose.mp.material3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "ro.safetyplease.ui.resources"
}

// `./gradlew test`, as CI runs it, runs the shared tests here on the JVM
tasks.register("test") {
    description = "Runs the shared tests on the JVM."
    group = "verification"
    dependsOn("testAndroidHostTest")
}
