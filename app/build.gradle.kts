plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("dev.rikka.tools.refine")
}

android {
    namespace = "com.sakuhome.launcher"
    compileSdk = 36


    defaultConfig {
        applicationId = "com.sakuhome.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    buildFeatures { aidl = true; buildConfig = true }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    compileOnly(project(":hidden-api-stub"))
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.navigation:navigation-fragment:2.9.7")
    implementation("androidx.navigation:navigation-ui:2.9.7")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.tools.refine:runtime:4.4.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("androidx.lifecycle:lifecycle-livedata:2.10.0")
}
