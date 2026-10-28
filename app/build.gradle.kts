plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.airecorder.mvp"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.airecorder.mvp"
        minSdk = 29
        targetSdk = 35
        versionCode = 42
        versionName = "0.6.1-android16-compatibility"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val backendUrl = providers.gradleProperty("AI_BACKEND_URL")
            .orElse("https://example.invalid")
            .get()
        buildConfigField("String", "AI_BACKEND_URL", "\"$backendUrl\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Core FTP tests exercise real sockets on the host JVM. Android Log calls
    // used by the production client are intentionally no-ops in that test VM.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    androidTestImplementation(platform(libs.compose.bom))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.material)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.concentus)
    implementation(libs.sqlcipher)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
