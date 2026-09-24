// Root build file: plugin versions only, applied in the modules that need them.
//
// NOTE: there is deliberately no `org.jetbrains.kotlin.android` alias here.
// AGP 9 ships built-in Kotlin support; applying the Kotlin Android plugin to an
// AGP 9 application module fails with "Cannot add extension with name 'kotlin'".
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose) apply false
}
