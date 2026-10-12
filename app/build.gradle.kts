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
        applicationId = "org.pingup.app"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    // JNA also ships armeabi, mips and mips64, which no supported phone runs and libsodium doesn't have.
    packaging {
        jniLibs {
            excludes += listOf("lib/armeabi/**", "lib/mips/**", "lib/mips64/**")
        }
    }

    // The Play bundle (./gradlew bundleRelease) is signed with the same release key as the APK, taken as the upload key.
    // Both languages stay in every install: changing the phone's language must not need the store.
    bundle {
        language {
            enableSplit = false
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }
}

/**
 * The texts live in :ui (composeResources), one copy for both apps. The Android code that isn't a shared screen
 * (notifications, widgets, the screens not moved yet) reads the same files as R.string, copied here at build time.
 */
abstract class SharedStrings : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val composeResources: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        composeResources.get().asFile.listFiles()?.filter { it.isDirectory && it.name.startsWith("values") }?.forEach { dir ->
            File(dir, "strings.xml").takeIf { it.exists() }?.copyTo(File(out, "${dir.name}/strings.xml"))
        }
    }
}

val sharedStrings = tasks.register<SharedStrings>("sharedStrings") {
    composeResources.set(rootProject.layout.projectDirectory.dir("ui/src/commonMain/composeResources"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(sharedStrings, SharedStrings::outputDir)
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
