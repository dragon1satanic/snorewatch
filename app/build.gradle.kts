import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

// Release signing credentials live in keystore.properties (gitignored).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// A fresh clone has no keystore.properties, so release signing is simply unavailable
// there. Derive that once and use it for both the config and the build type: creating
// the signing config unconditionally would call rootProject.file("") and fail every
// build (debug included) at configuration time.
val releaseStoreFile = keystoreProperties.getProperty("storeFile")
val hasReleaseSigning = !releaseStoreFile.isNullOrBlank()

android {
    namespace = "com.snorewatch.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.snorewatch.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    // The TFLite model must stay uncompressed so it can be memory-mapped from assets.
    aaptOptions {
        noCompress += "tflite"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.tensorflow:tensorflow-lite-task-audio:0.4.4")
}
