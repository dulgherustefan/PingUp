plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appName: String = providers.gradleProperty("appName").get()

// The version comes from the release tag (-PversionName=1.2.3); versionCode grows with it.
val appVersion: String = providers.gradleProperty("versionName").getOrElse("0.1.0")
val appVersionCode: Int = appVersion.split(".").mapNotNull { it.toIntOrNull() }.let { parts ->
    require(parts.size == 3 && parts[1] < 100 && parts[2] < 100) { "versionName must be X.Y.Z with Y, Z under 100, not $appVersion" }
    parts[0] * 10000 + parts[1] * 100 + parts[2]
}

// The release key lives in GitHub secrets; without it, assembleRelease is left unsigned.
fun env(name: String): String? = providers.environmentVariable(name).orNull
val releaseKeystore: String? = env("PINGUP_KEYSTORE")

android {
    namespace = "ro.safetyplease.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ro.safetyplease.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        resValue("string", "app_name", appName)
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = env("PINGUP_KEYSTORE_PASSWORD")
                keyAlias = env("PINGUP_KEY_ALIAS")
                keyPassword = env("PINGUP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The app pins its own language (Romanian), so per-language bundle splits have nothing to split.
    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)

    // "@aar" also turns off transitive deps; otherwise lazysodium pulls jna.jar on top of jna.aar and classes clash.
    implementation("${libs.lazysodium.android.get()}@aar")
    implementation("${libs.jna.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
