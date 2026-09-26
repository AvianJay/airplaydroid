plugins {
    alias(libs.plugins.android.application)
    // Deliberately NO kotlin-android alias here. AGP 9 ships built-in Kotlin, and
    // applying org.jetbrains.kotlin.android to an AGP 9 application module fails
    // with "Cannot add extension with name 'kotlin'".
    alias(libs.plugins.compose)
}

// ---------------------------------------------------------------------------
// Versioning.
//
// The in-app updater compares versionCode, because that is the only number the
// package manager will accept an upgrade on. It must therefore be strictly
// increasing across EVERY build ever published -- from main and from a tag --
// or a genuinely newer APK is refused with INSTALL_FAILED_VERSION_DOWNGRADE,
// the one updater failure the user cannot work around.
//
// Both channels therefore share one monotonic sequence: the git commit count.
//
//   nightly  ->  code = commits on main, name = 0.1.0-nightly.<n>.<sha>
//   release  ->  code = commits at the tag, name = the tag (v1.2.3 -> 1.2.3)
//
// A later build always has a higher count than an earlier one no matter which
// channel produced it, so moving between channels can only ever be an upgrade.
// Deriving a release's code from its version instead (0.1.0 -> 100) would break
// exactly that: 100 is far below any nightly's code, so a user on a nightly
// would be told the 0.1.0 release is *older* than what they already have.
//
// CI supplies the count; a local build has no meaningful one, so it falls back
// to a code derived from the base version and is never published.
// ---------------------------------------------------------------------------

val baseVersion = (providers.gradleProperty("app.version.base").orNull ?: "0.1.0").trim()

/** `1.2.3` -> `10203`. The local-build fallback, never used by CI. */
fun codeFromVersion(version: String): Int {
    val parts = version.split('.').mapNotNull { it.takeWhile(Char::isDigit).toIntOrNull() }
    val major = parts.getOrElse(0) { 0 }
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    return major * 10_000 + minor * 100 + patch
}

// CI passes both; a local build leaves them unset and gets the base version.
val ciVersionName = providers.environmentVariable("APP_VERSION_NAME").orNull?.trim()?.takeIf { it.isNotEmpty() }
val ciVersionCode = providers.environmentVariable("APP_VERSION_CODE").orNull?.trim()?.toIntOrNull()

val appVersionName: String = ciVersionName ?: baseVersion
val appVersionCode: Int = ciVersionCode ?: codeFromVersion(baseVersion)

// Read from gradle.properties so a fork changes one line and nothing else. The
// two channel manifest URLs are derived from the repository in Kotlin, so this
// is the only place the repository name appears.
val updateRepository: String = providers.gradleProperty("app.update.repository").orNull?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "AvianJay/airplaydroid"
val releasesPageUrl: String = providers.gradleProperty("app.update.releasesPage").orNull?.trim()
    ?.takeIf { it.isNotEmpty() } ?: "https://github.com/AvianJay/airplaydroid/releases"

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
        versionCode = appVersionCode
        versionName = appVersionName

        // The updater reads these, so a fork changes gradle.properties and
        // nothing else.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
        buildConfigField("String", "RELEASES_PAGE_URL", "\"$releasesPageUrl\"")
    }

    // Release signing comes from the environment, never from the repo: CI decodes
    // the KEYSTORE_BASE64 secret to a file and sets KEYSTORE_FILE, KEYSTORE_ALIAS
    // and KEYSTORE_PASSWORD (which serves as the key password too). Without them,
    // e.g. on a local machine or a fork's pull request, the release build is
    // simply left unsigned.
    val releaseKeystore = providers.environmentVariable("KEYSTORE_FILE").orNull
        ?.let(::file)
        ?.takeIf { it.isFile }
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KEYSTORE_ALIAS").get()
                keyPassword = providers.environmentVariable("KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        // Off by default under AGP 9; the updater's manifest and release-page
        // URLs are generated into BuildConfig from gradle.properties.
        buildConfig = true
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
