import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Required. With only the java{} block above, compileKotlin would take its target
// from the Gradle daemon JDK (21) while javac targets 17, and KGP's JVM-target
// validation fails the build. jvmToolchain(17) is NOT an option here: only JDK 21
// is installed and there is no foojay resolver configured.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // Ed25519 for legacy AirPlay pairing. Used via the lightweight API only --
    // never Security.addProvider, which collides with Conscrypt on Android.
    implementation(libs.bouncycastle)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
