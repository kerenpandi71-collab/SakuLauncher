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
        versionCode = 5
        versionName = "0.5.0"
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
    implementation(project(":trackpad"))
    implementation("dev.rikka.shizuku:api:13.1.5")
}
