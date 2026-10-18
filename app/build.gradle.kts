plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sakuhome.launcher"
    compileSdk = 36


    defaultConfig {
        applicationId = "com.sakuhome.launcher"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"

        // Target Redmi 12C ARM64 and avoid packaging unused GeckoView ABIs.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Embedded Gecko engine keeps YouTube inside Saku rather than opening a default browser.
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:158.0.20261007201254")
}
