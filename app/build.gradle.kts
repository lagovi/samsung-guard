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
            // Committed throwaway debug key — same signature on every build, so
            // each artifact installs over the previous one. Override with
            // ANDROID_DEBUG_KEYSTORE for a locally generated key.
            storeFile = file(System.getenv("ANDROID_DEBUG_KEYSTORE")
               ?.takeIf { it.isNotBlank() }
                ?: "${rootDir}/keystore/debug.keystore")
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