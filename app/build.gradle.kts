plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kokoro.tts"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kokoro.tts"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile     = file("release.keystore")
            storePassword = System.getenv("KS_PASS")     ?: "kokorotts2026"
            keyAlias      = System.getenv("KS_ALIAS")    ?: "kokoro"
            keyPassword   = System.getenv("KS_KEY_PASS") ?: "kokorotts2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { 
        jvmTarget = "17" 
    }

    buildFeatures { viewBinding = true }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/*"
        }
    }

    androidResources {
        noCompress += listOf("litertlm", "tflite", "lite", "bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Stable TensorFlow Lite (LiteRT is the new name for TFLite, same API)
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
}
