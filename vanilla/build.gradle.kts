plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the key never lives in the repository. CI decodes it from encrypted GitHub
// secrets into a temp file and passes these variables; without them (local builds, forks,
// pull requests) release builds are left unsigned.
val signStore: String? = System.getenv("OPENMK3_KEYSTORE")

android {
    namespace = "org.openmk3.vanilla"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.openmk3.vanilla"
        minSdk = 33
        targetSdk = 35
        versionCode = 80
        versionName = "0.65.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signStore != null) {
            create("release") {
                storeFile = file(signStore)
                storePassword = System.getenv("OPENMK3_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("OPENMK3_KEY_ALIAS")
                keyPassword = System.getenv("OPENMK3_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
}
