plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cybermiller.guard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cybermiller.guard"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Committed throwaway debug key: keeps every CI artifact installable
        // over the previous one (same signature).
        getByName("debug") {
            // Repo keystore unless ANDROID_DEBUG_KEYSTORE points elsewhere.
            // CI uses this committed key; a local build uses ~/.config/.android,
            // which holds the key the device already has installed.
            storeFile = file(System.getenv("ANDROID_DEBUG_KEYSTORE") ?: "${System.getProperty("user.home")}/.config/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}