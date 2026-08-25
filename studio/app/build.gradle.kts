plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mkmemories.mkstudio"
    compileSdk = 35
    // Version du NDK par défaut d'AGP 8.5 : présente/téléchargeable sur les runners CI.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.mkmemories.mkstudio"
        // Android 10+ : la génération sur l'appareil exige de toute façon un
        // téléphone récent (6 Go de RAM et plus), tous sous Android 10+.
        minSdk = 29
        targetSdk = 35
        versionCode = 8
        versionName = "1.7"
        ndk {
            // Moteur 64 bits uniquement.
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("personal") {
            storeFile = file("../signing/mkstudio.p12")
            storeType = "PKCS12"
            storePassword = "mkstudio"
            keyAlias = "mkstudio"
            keyPassword = "mkstudio"
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("io.coil-kt:coil:2.6.0")
}
