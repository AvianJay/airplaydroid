plugins {
    alias(libs.plugins.android.application)
    // Deliberately NO kotlin-android alias here. AGP 9 ships built-in Kotlin, and
    // applying org.jetbrains.kotlin.android to an AGP 9 application module fails
    // with "Cannot add extension with name 'kotlin'".
    alias(libs.plugins.compose)
}

android {
    namespace = "tw.avianjay.airplaydroid"

    // AGP 9 block DSL. Verified against AGP 9.1.1 with `gradlew :app:tasks`.
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "tw.avianjay.airplaydroid"
        minSdk { version = release(26) }
        targetSdk { version = release(36) }
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // No kotlinOptions block: under built-in Kotlin the JVM target is inferred
    // from targetCompatibility above.

    lint {
        abortOnError = true
        // These three only ever say "a newer version exists". Our versions are
        // pinned deliberately -- Compose 1.12.0+, androidx.core 1.18.0+ and
        // lifecycle 2.11.0+ raise minCompileSdk above 36 and hard-fail
        // checkDebugAarMetadata, and AGP above 9.1 breaks sync with the installed
        // Android Studio (see README). Silencing the nags keeps a real lint error
        // from being lost in the noise.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
        )
    }
}

dependencies {
    implementation(project(":protocol"))

    // androidx.core:core, NOT core-ktx (empty artifact from 1.19.0 onwards).
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
