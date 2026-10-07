import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// App logic without Android: protocol, mesh, crypto, chat, incidents and the event map.
// Runs and is tested on the plain JVM; app/ only adds the Android adapters (Bluetooth, location, keystore) and the UI.
plugins {
    // the Kotlin plugin is already on the classpath from the root build.gradle.kts, so no version here
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // lazysodium-java declares Java 21 only to run the tests (Android Studio's JDK is 21);
    // our bytecode stays Java 17, as in app/.
    disableAutoTargetJvm()
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // On the phone the LazySodium classes come from lazysodium-android (same API); here we only compile against the Java one.
    compileOnly(libs.lazysodium.java)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
}
