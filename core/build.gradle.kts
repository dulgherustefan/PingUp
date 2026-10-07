import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logica aplicatiei fara Android: protocolul, reteaua mesh, criptografia, chatul, incidentele si harta evenimentului.
// Ruleaza si se testeaza pe JVM simplu; app/ aduce doar adaptoarele Android (Bluetooth, locatie, keystore) si interfata.
plugins {
    // pluginul Kotlin e deja pe classpath din build.gradle.kts de la radacina, deci fara versiune aici
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // lazysodium-java cere Java 21 doar ca sa ruleze testele (JDK-ul din Android Studio e 21);
    // bytecode-ul nostru ramane Java 17, ca in app/.
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
    // Pe telefon, clasele LazySodium vin din lazysodium-android, care are acelasi API; aici doar compilam pe varianta Java.
    compileOnly(libs.lazysodium.java)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
}
