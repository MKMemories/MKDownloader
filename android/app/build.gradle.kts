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
        versionCode = 43
        versionName = "5.28"
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
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lecteur vidéo premium (streaming direct des flux extraits par yt-dlp).
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Session média : lecture en arrière-plan + notification/écran verrouillé.
    implementation("androidx.media3:media3-session:1.4.1")
    // Couleurs dynamiques extraites de la pochette (dégradé premium).
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Cast (Chromecast, Google TV, enceintes & TV compatibles).
    implementation("androidx.media3:media3-cast:1.4.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")

    // Moteur : Python + yt-dlp + ffmpeg empaquetés pour Android.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
