plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Firebase reads its project settings from google-services.json (downloaded from the
// Firebase console). Until that file is added the app builds and runs with analytics off.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.lapsepaint.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lapsepaint.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.5.1"
    }

    buildTypes {
        release {
            // Lite build: R8 strips unused code and renames what's left; unused resources are dropped.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the local debug key so it can be installed directly (not for the Play Store).
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-analytics")
}
