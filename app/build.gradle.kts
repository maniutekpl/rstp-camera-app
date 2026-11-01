// Kotlin support is built into AGP 9; its JVM target follows compileOptions below.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.domowe.kamery"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.domowe.kamery"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // LibVLC ships ~50 MB of native code per CPU type, so build one APK per phone CPU
    // instead of one APK with all four. x86/x86_64 (emulators only) are left out.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")

    // Saves the camera list on the phone.
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // VLC engine: decodes RTSP streams from most camera brands.
    implementation("org.videolan.android:libvlc-all:3.7.7")
}
