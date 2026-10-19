plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sakuhome.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sakuhome.launcher"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
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
    implementation("dev.rikka.shizuku:api:13.1.5")
    // GeckoView is required for real WebExtension support; Android WebView cannot host uBlock Origin.
    // The previous version was not published in the Mozilla Maven repository, so use a valid published build.
    implementation("org.mozilla.geckoview:geckoview:158.0.20261007201254")
}

// Bundle uBlock Origin as a built-in extension. Place the XPI in app/src/main/assets/extensions/
// and enable via GeckoRuntime's WebExtensionController at runtime.
