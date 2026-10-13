import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.jetbrains.kotlin.konan.target.HostManager
import java.time.Duration

// App logic without Android: protocol, mesh, crypto, chat, incidents and the event map.
// Kotlin Multiplatform: app/ uses the JVM target, where most tests run; the iOS targets are the same code for the
// iPhone app, with libsodium linked through cinterop. app/ only adds the Android adapters and the UI.
plugins {
    // the Kotlin plugin is already on the classpath from the root build.gradle.kts, so no version here
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.kotlin.serialization)
}

// libsodium for the iOS targets, built from the signed release by tools/build_libsodium_ios.sh (macOS and Xcode only)
val libsodium = layout.buildDirectory.dir("libsodium").get().asFile
val buildLibsodium = tasks.register<Exec>("buildLibsodiumIos") {
    description = "Builds libsodium as static libraries for the iOS targets."
    onlyIf { HostManager.hostIsMac }
    commandLine(rootProject.file("tools/build_libsodium_ios.sh").path, libsodium.path)
    outputs.file(File(libsodium, "ios-arm64/lib/libsodium.a"))
    outputs.file(File(libsodium, "ios-simulator-arm64/lib/libsodium.a"))
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }
    // Apple Silicon only: the x86_64 Apple targets are tier 3 since Kotlin 2.3
    listOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-simulator-arm64").forEach { (target, slice) ->
        target.compilations.getByName("main").cinterops.create("sodium") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/sodium.def"))
            includeDirs(File(libsodium, "$slice/include"))
            extraOpts("-libraryPath", File(libsodium, "$slice/lib").path)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            api(libs.okio)
            api(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            // On the phone the LazySodium classes come from lazysodium-android (same API); here we only compile against the Java one.
            compileOnly(libs.lazysodium.java)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.lazysodium.java)
            implementation(libs.jna)
        }
    }
}

tasks.withType<CInteropProcess>().configureEach { dependsOn(buildLibsodium) }

// The tests read the golden vectors and the app's assets by path: the iPhone simulator runs them off the Mac's disk too
val testEnvironment = tasks.register("testEnvironment") {
    val root = rootDir.absolutePath
    val output = layout.buildDirectory.dir("generated/testEnvironment")
    inputs.property("root", root)
    outputs.dir(output)
    doLast {
        val file = output.get().file("ro/safetyplease/core/TestEnvironment.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package ro.safetyplease.core\n\n/** Where the repository is on the machine that built the tests. */\n" +
                "internal const val REPO_ROOT = \"" + root.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"\n",
        )
    }
}
kotlin.sourceSets.commonTest { kotlin.srcDir(testEnvironment) }

// kotlin.test has no per-test timeout as JUnit had: a test stuck in a loop fails the run here instead of holding CI
tasks.withType<AbstractTestTask>().configureEach { timeout.set(Duration.ofMinutes(15)) }

// `./gradlew test`, as CI and the release workflow run it, keeps running the JVM tests here
tasks.register("test") {
    description = "Runs the JVM tests."
    group = "verification"
    dependsOn("jvmTest")
}
