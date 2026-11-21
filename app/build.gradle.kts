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

        // Secrets are injected from gitignored sources only (audit 2026-11-21):
        // locally from local.properties, in CI from GitHub Actions env/secret.
        fun secret(name: String): String {
            val local = rootDir.resolve("local.properties").takeIf { it.isFile }
                ?.inputStream()?.use { p -> java.util.Properties().apply { load(p) } }
            return System.getenv(name)
                ?: local?.getProperty(name)
                ?: ""
        }
        fun field(name: String): String =
            "\"" + (secret(name)).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        buildConfigField("String", "TELEGRAM_BOT_TOKEN", field("TELEGRAM_BOT_TOKEN"))
        buildConfigField("String", "TELEGRAM_CHAT_ID", field("TELEGRAM_CHAT_ID"))
        buildConfigField("String", "VIBER_OWNER_PHONE", field("VIBER_OWNER_PHONE"))
        buildConfigField("String", "LLM_API_KEY", field("LLM_API_KEY"))
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Same debug key as the custom Viber "api" build (uber-apk-signer's
        // bundled debug.keystore, CN=Android Debug). Viber's LiteAPI provider
        // only accepts packages signed with that key — this is what lets Guard
        // call content://com.viber.voip.liteapi directly, no root needed.
        // The keystore is NOT committed (audit 2026-11-21 policy: *.keystore is
        // gitignored); CI extracts it from the pinned official uber-apk-signer
        // jar (see .github/workflows/build.yml). Locally point
        // ANDROID_DEBUG_KEYSTORE at a copy of the same file.
        getByName("debug") {
            storeFile = file(System.getenv("ANDROID_DEBUG_KEYSTORE")
                ?.takeIf { it.isNotBlank() }
                ?: "${rootDir}/keystore/uber-debug.keystore")
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