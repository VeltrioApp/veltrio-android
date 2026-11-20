plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.veltrio.shared"
    compileSdk = 37

    defaultConfig {
        // Android 8.0, as the core's build (VELTRIO_ANDROID_API).
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The Rust core, downloaded by scripts/fetch-core.sh into core/: one .so
    // per ABI and its UniFFI Kotlin bindings.
    sourceSets["main"].apply {
        jniLibs.directories.add("../core/jniLibs")
        kotlin.directories.add("../core/kotlin")
    }

    buildFeatures { compose = true }
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.coroutines.android)
    api(libs.serialization.json)
    api(libs.okhttp)
    // UniFFI's Kotlin bindings call the .so through JNA (the Android aar).
    api("${libs.jna.get().module}:${libs.jna.get().version}@aar")
    api(libs.libmpv)
}
