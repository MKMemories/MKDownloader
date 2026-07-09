plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mkmemories.mkdownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mkmemories.mkdownloader"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk {
            // youtubedl-android ne publie des binaires (Python/ffmpeg) que pour ARM.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("personal") {
            storeFile = file("../signing/mkdownloader.p12")
            storeType = "PKCS12"
            storePassword = "mkdownloader"
            keyAlias = "mkdownloader"
            keyPassword = "mkdownloader"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true }

    packaging {
        // Requis par youtubedl-android : ses binaires natifs doivent être
        // extraits sur le disque au lieu d'être chargés depuis l'APK.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.6.0")

    // Moteur : Python + yt-dlp + ffmpeg empaquetés pour Android.
    implementation("com.github.yausername.youtubedl-android:library:0.17.2")
    implementation("com.github.yausername.youtubedl-android:ffmpeg:0.17.2")
}
