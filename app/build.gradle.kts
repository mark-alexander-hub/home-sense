import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: a keystore.properties OUTSIDE the repo, found via HOME_SENSE_KEYSTORE_PROPS
// or %LOCALAPPDATA%\home-sense-keys\keystore.properties. Without it the release build is unsigned.
val keystoreProps = Properties().apply {
    listOfNotNull(
        System.getenv("HOME_SENSE_KEYSTORE_PROPS"),
        System.getenv("LOCALAPPDATA")?.let { "$it/home-sense-keys/keystore.properties" }
    ).map { file(it) }.firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "io.github.markalexanderhub.homesense"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.markalexanderhub.homesense"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Newer versions of these (core 1.19, material 1.14 as of Oct 2026)
    // need compileSdk 37 and Android Gradle plugin 9.1. Bump them together when the toolchain moves.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")


    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
