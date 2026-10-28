buildscript {
    dependencies {
        // AGP's built-in Kotlin defaults to 2.2.10. Putting a newer compiler on
        // the buildscript classpath raises that default for the whole build.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
