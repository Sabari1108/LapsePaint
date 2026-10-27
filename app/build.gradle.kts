import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase reads its project settings from google-services.json (downloaded from the
// Firebase console). Until that file is added the app builds and runs with analytics off.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Play Store signing: the key and its passwords live outside the project (never in git).
val releaseKeys = Properties().apply {
    val f = file(System.getProperty("user.home") + "/LapsePaint-keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.lapsepaint.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lapsepaint.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 5
        versionName = "1.6"
    }

    signingConfigs {
        if (releaseKeys.isNotEmpty()) create("upload") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // Lite build: R8 strips unused code and renames what's left; unused resources are dropped.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The Play Store key when it's on this machine, otherwise the local debug key.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Menus and dialogs use Miuix (HyperOS-style Compose components); the canvas stays plain Views.
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
}
